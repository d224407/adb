package com.min.adb

import com.min.adb.adb.AdbConnection
import com.min.adb.adb.AdbStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import kotlin.concurrent.thread

/**
 * A persistent interactive shell on the device: one `shell:` stream (adbd gives it a PTY, like
 * `adb shell`), so cd/env/history persist, output streams live, Ctrl-C works. Output goes through
 * a small terminal emulator: a growing list of lines with a cursor, so \r overwrite, backspace,
 * tabs, erase-line/screen, cursor moves and delete/insert chars (shell line editing, history,
 * tab completion) render correctly. Colors and other attributes are dropped.
 */
class TerminalSession(private val connection: AdbConnection, private val onChange: () -> Unit) {
    @Volatile private var stream: AdbStream? = null
    private val writer = Executors.newSingleThreadExecutor()
    @Volatile var ended = false
        private set

    private val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE)
    private var carry = ByteArray(0)

    // Screen model, guarded by `lines`.
    private val lines = ArrayList<StringBuilder>().apply { add(StringBuilder()) }
    private var row = 0
    private var col = 0

    private var state = NORMAL
    private val csi = StringBuilder()

    init {
        // Everything touching the socket (opening the stream included) stays off the UI thread.
        thread(name = "adb-terminal-read", isDaemon = true) {
            try {
                val st = connection.open("shell:")
                stream = st
                while (!ended) {
                    val chunk = st.read(1000)
                    if (chunk == null) { if (st.closed) break else continue }
                    if (chunk.isEmpty()) { if (st.closed) break else continue }
                    feed(decode(chunk))
                    onChange()
                }
            } catch (_: Exception) { }
            synchronized(lines) {
                row = lines.size - 1
                if (lines[row].isNotEmpty()) { lines.add(StringBuilder()); row++ }
                lines[row].append("[session closed]")
                lines.add(StringBuilder())
                row++
                col = 0
            }
            ended = true
            onChange()
        }
    }

    fun text(): String = synchronized(lines) { lines.joinToString("\n") }

    fun clear() { synchronized(lines) { reset() }; onChange() }

    fun sendLine(line: String) = send((line + "\n").toByteArray(Charsets.UTF_8))

    fun send(bytes: ByteArray) {
        if (ended) return
        try {
            writer.execute {
                try {
                    // The reader thread opens the stream; wait briefly for it on first use.
                    var waited = 0
                    while (stream == null && !ended && waited < 5000) { Thread.sleep(20); waited += 20 }
                    val st = stream ?: return@execute
                    // Small frames: older adbd builds only accept 4 KB per WRTE.
                    var off = 0
                    while (off < bytes.size && !ended) {
                        val n = minOf(WRITE_CHUNK, bytes.size - off)
                        st.write(bytes.copyOfRange(off, off + n))
                        off += n
                    }
                } catch (_: Exception) { }
            }
        } catch (_: RejectedExecutionException) { }
    }

    fun close() {
        ended = true
        runCatching { writer.shutdownNow() }
        Thread { runCatching { stream?.close() } }.start()
    }

    private fun decode(chunk: ByteArray): String {
        val bytes = carry + chunk
        val input = ByteBuffer.wrap(bytes)
        val out = CharBuffer.allocate(bytes.size + 1)
        decoder.reset()
        decoder.decode(input, out, false)
        out.flip()
        carry = ByteArray(input.remaining()).also { input.get(it) }
        return out.toString()
    }

    private fun feed(s: String) = synchronized(lines) {
        for (ch in s) {
            when (state) {
                NORMAL -> when (ch) {
                    '\u001B' -> state = ESC
                    '\r' -> col = 0
                    '\n' -> lineFeed()
                    '\b' -> if (col > 0) col--
                    '\t' -> col = (col / 8 + 1) * 8
                    else -> if (ch >= ' ') put(ch)
                }
                ESC -> state = when (ch) {
                    '[' -> { csi.setLength(0); CSI }
                    ']' -> OSC
                    '(', ')' -> SKIP1
                    'c' -> { reset(); NORMAL }
                    else -> NORMAL
                }
                CSI -> if (ch in '@'..'~') { runCsi(ch); state = NORMAL } else csi.append(ch)
                OSC -> if (ch == '\u0007') state = NORMAL else if (ch == '\u001B') state = ESC
                SKIP1 -> state = NORMAL
            }
        }
        trim()
    }

    private fun reset() {
        lines.clear()
        lines.add(StringBuilder())
        row = 0
        col = 0
    }

    private fun put(ch: Char) {
        val line = lines[row]
        while (line.length < col) line.append(' ')
        if (col < line.length) line.setCharAt(col, ch) else line.append(ch)
        col++
    }

    /** The tty turns \n into \r\n on output, so a line feed also returns to column 0. */
    private fun lineFeed() {
        row++
        if (row >= lines.size) lines.add(StringBuilder())
        col = 0
    }

    private fun runCsi(cmd: Char) {
        val raw = csi.toString()
        if (raw.startsWith("?") || raw.startsWith(">") || raw.startsWith("=") || raw.startsWith("!")) return
        val params = raw.split(';').map { it.toIntOrNull() }
        fun p(i: Int, def: Int): Int = params.getOrNull(i) ?: def
        val n = maxOf(1, p(0, 1))
        val line = lines[row]
        when (cmd) {
            'K' -> when (p(0, 0)) {
                0 -> if (col < line.length) line.setLength(col)
                1 -> for (i in 0 until minOf(col + 1, line.length)) line.setCharAt(i, ' ')
                2 -> line.setLength(0)
            }
            'J' -> when (p(0, 0)) {
                0 -> {
                    if (col < line.length) line.setLength(col)
                    while (lines.size > row + 1) lines.removeAt(lines.size - 1)
                }
                2, 3 -> reset()
            }
            'C' -> col += n
            'D' -> col = maxOf(0, col - n)
            'A' -> row = maxOf(0, row - n)
            'B' -> { row += n; while (lines.size <= row) lines.add(StringBuilder()) }
            'G' -> col = maxOf(0, p(0, 1) - 1)
            'H', 'f' -> {
                val top = maxOf(0, lines.size - SCREEN_ROWS)
                row = minOf(lines.size - 1, top + maxOf(1, p(0, 1)) - 1)
                col = maxOf(0, p(1, 1) - 1)
            }
            'P' -> if (col < line.length) line.delete(col, minOf(line.length, col + n))
            '@' -> {
                while (line.length < col) line.append(' ')
                line.insert(col, " ".repeat(n))
            }
            'X' -> for (i in col until minOf(line.length, col + n)) line.setCharAt(i, ' ')
        }
    }

    private fun trim() {
        if (lines.size > MAX_LINES) {
            val drop = lines.size - MAX_LINES + MAX_LINES / 10
            repeat(drop) { lines.removeAt(0) }
            row = maxOf(0, row - drop)
        }
        val line = lines[row]
        if (line.length > MAX_LINE) {
            val cut = line.length - MAX_LINE
            line.delete(0, cut)
            col = maxOf(0, col - cut)
        }
    }

    private companion object {
        const val NORMAL = 0; const val ESC = 1; const val CSI = 2; const val OSC = 3; const val SKIP1 = 4
        const val MAX_LINES = 3000
        const val MAX_LINE = 8000
        const val SCREEN_ROWS = 24
        const val WRITE_CHUNK = 4096
    }
}

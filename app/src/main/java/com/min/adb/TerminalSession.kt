package com.min.adb

import com.min.adb.adb.AdbConnection
import com.min.adb.adb.AdbStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * A persistent interactive shell on the device: one `shell:` stream (adbd gives it a PTY, like
 * `adb shell`), so cd/env/history persist, output streams live, Ctrl-C works. Output is run through
 * a tiny terminal emulator (ANSI escapes stripped, \r overwrite, backspace, clear screen).
 */
class TerminalSession(private val connection: AdbConnection, private val onChange: () -> Unit) {
    @Volatile private var stream: AdbStream? = null
    private val model = StringBuilder()
    private val writer = Executors.newSingleThreadExecutor()
    @Volatile var ended = false
        private set

    private val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE)
    private var carry = ByteArray(0)

    private var state = NORMAL
    private val csi = StringBuilder()
    private var pendingCr = false

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
            synchronized(model) { if (model.isNotEmpty() && model.last() != '\n') model.append('\n'); model.append("[session closed]\n") }
            ended = true
            onChange()
        }
    }

    fun text(): String = synchronized(model) { model.toString() }

    fun clear() { synchronized(model) { model.setLength(0) }; onChange() }

    fun sendLine(line: String) = send((line + "\n").toByteArray(Charsets.UTF_8))

    fun send(bytes: ByteArray) {
        if (ended) return
        writer.execute {
            try {
                // The reader thread opens the stream; wait briefly for it on first use.
                var waited = 0
                while (stream == null && !ended && waited < 5000) { Thread.sleep(20); waited += 20 }
                stream?.write(bytes)
            } catch (_: Exception) { }
        }
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

    private fun feed(s: String) = synchronized(model) {
        for (ch in s) {
            when (state) {
                NORMAL -> when (ch) {
                    '\u001B' -> state = ESC
                    '\r' -> pendingCr = true
                    '\n' -> { pendingCr = false; model.append('\n') }
                    '\b' -> if (model.isNotEmpty() && model.last() != '\n') model.setLength(model.length - 1)
                    '\t' -> { applyCr(); model.append('\t') }
                    else -> if (ch >= ' ') { applyCr(); model.append(ch) }
                }
                ESC -> state = when (ch) {
                    '[' -> { csi.setLength(0); CSI }
                    ']' -> OSC
                    '(', ')' -> SKIP1
                    else -> NORMAL
                }
                CSI -> if (ch in '@'..'~') {
                    if (ch == 'J' && (csi.toString() == "2" || csi.toString() == "3")) model.setLength(0)
                    state = NORMAL
                } else csi.append(ch)
                OSC -> if (ch == '\u0007') state = NORMAL else if (ch == '\u001B') state = ESC
                SKIP1 -> state = NORMAL
            }
        }
        if (model.length > MAX_CHARS) {
            val cut = model.indexOf("\n", model.length - MAX_CHARS + 20_000)
            model.delete(0, if (cut > 0) cut + 1 else model.length - MAX_CHARS)
        }
    }

    /** A lone \r returns to column 0: the next printable char overwrites the current line. */
    private fun applyCr() {
        if (!pendingCr) return
        pendingCr = false
        model.setLength(model.lastIndexOf("\n") + 1)
    }

    private companion object {
        const val NORMAL = 0; const val ESC = 1; const val CSI = 2; const val OSC = 3; const val SKIP1 = 4
        const val MAX_CHARS = 120_000
    }
}

package com.min.adb

import android.os.Handler
import android.os.Looper
import com.min.adb.adb.AdbConnection
import com.min.adb.adb.AdbStream
import com.min.adb.adb.Utf8StreamDecoder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

data class LogEntry(
    val id: Long,
    val date: String,
    val time: String,
    val pid: String,
    val tid: String,
    val priority: Char,
    val tag: String,
    val msg: String
)

/** Streams `logcat -v long` from the device and keeps the newest [CAPACITY] parsed entries. */
class LogcatSession(private val connection: AdbConnection) {
    /** Called on the UI thread, at most every [UI_INTERVAL_MS], after new entries arrive. */
    val listeners = CopyOnWriteArrayList<() -> Unit>()

    @Volatile var ended = false
        private set
    @Volatile private var stopped = false
    @Volatile private var stream: AdbStream? = null

    private val lock = Any()
    private val entries = ArrayList<LogEntry>()
    private var nextId = 0L
    private val ui = Handler(Looper.getMainLooper())
    private val notifyScheduled = AtomicBoolean(false)

    fun start() {
        thread(name = "adb-logcat", isDaemon = true) {
            try {
                val st = connection.open("shell:logcat -v long")
                stream = st
                if (stopped) { runCatching { st.close() }; return@thread }
                read(st)
            } catch (_: Exception) {
            }
            ended = true
            notifyUi()
        }
    }

    fun snapshot(): List<LogEntry> = synchronized(lock) { ArrayList(entries) }

    fun clear() {
        synchronized(lock) { entries.clear() }
        thread(isDaemon = true) { runCatching { connection.shell("logcat -c") } }
        notifyUi()
    }

    fun stop() {
        stopped = true
        listeners.clear()
        val st = stream
        if (st != null) thread(isDaemon = true) { runCatching { st.close() } }
    }

    private fun read(st: AdbStream) {
        val decoder = Utf8StreamDecoder()
        val buf = StringBuilder()
        var meta: Meta? = null
        val msg = StringBuilder()

        fun finish() {
            val m = meta ?: return
            meta = null
            add(LogEntry(0, m.date, m.time, m.pid, m.tid, m.priority, m.tag, msg.toString()))
            msg.setLength(0)
        }

        while (!stopped) {
            val chunk = st.read(1000)
            if (chunk == null || chunk.isEmpty()) { if (st.closed) break else continue }
            buf.append(decoder.decode(chunk))
            var start = 0
            while (true) {
                val nl = buf.indexOf("\n", start)
                if (nl < 0) break
                val line = buf.substring(start, nl).trimEnd('\r')
                start = nl + 1
                val parsed = if (line.startsWith("[")) parseMeta(line) else null
                when {
                    parsed != null -> { finish(); meta = parsed }
                    meta == null -> Unit
                    // A blank line ends an entry; it is shown right away, not when the next one arrives.
                    line.isEmpty() -> finish()
                    else -> { if (msg.isNotEmpty()) msg.append('\n'); msg.append(line) }
                }
            }
            buf.delete(0, start)
        }
    }

    private class Meta(val date: String, val time: String, val pid: String, val tid: String, val priority: Char, val tag: String)

    private fun parseMeta(line: String): Meta? {
        val m = META.matchEntire(line.trim()) ?: return null
        val g = m.groupValues
        return Meta(g[1], g[2], g[4], g[5], g[6][0], g[7])
    }

    private fun add(e: LogEntry) {
        synchronized(lock) {
            entries.add(e.copy(id = nextId++))
            if (entries.size > CAPACITY + TRIM) entries.subList(0, TRIM).clear()
        }
        notifyUi()
    }

    private fun notifyUi() {
        if (!notifyScheduled.compareAndSet(false, true)) return
        ui.postDelayed({
            notifyScheduled.set(false)
            if (!stopped) listeners.forEach { it() }
        }, UI_INTERVAL_MS)
    }

    companion object {
        const val CAPACITY = 20_000
        private const val TRIM = 2_000
        private const val UI_INTERVAL_MS = 150L
        // [ 11-18 06:05:12.123  1234: 1250 I/Tag ]   (an optional leading "uid:" is skipped)
        private val META = Regex("""\[\s*(\S+)\s+(\S+)\s+(?:(\S+?):\s*)?(\S+?):\s*(\S+)\s+([VDIWEFA])/(.*?)\s*]""")
    }
}

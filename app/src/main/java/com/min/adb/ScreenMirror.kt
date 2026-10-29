package com.min.adb

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import com.min.adb.adb.AdbConnection
import kotlin.concurrent.thread

/**
 * Live screen mirror, scrcpy-style: raw H.264 from the device is decoded with MediaCodec straight
 * onto a Surface. The video source is the device's own `screenrecord` (no extra server to install);
 * it caps each run at ~3 minutes, so it is restarted seamlessly while mirroring.
 */
class ScreenMirror(
    private val connection: AdbConnection,
    private val surface: Surface,
    private val options: Options,
    private val onSize: (width: Int, height: Int) -> Unit,
    private val onError: (String) -> Unit
) {
    /** maxSize 0 = native resolution. */
    data class Options(val maxSize: Int, val bitRate: Int)

    @Volatile private var stopped = false
    @Volatile var deviceWidth = 0
        private set
    @Volatile var deviceHeight = 0
        private set
    private var codec: MediaCodec? = null

    fun start() {
        thread(name = "adb-mirror", isDaemon = true) { run() }
    }

    fun stop() {
        stopped = true
    }

    private fun run() {
        // One mirror at a time: a new decoder can't attach to the surface until the old one is released.
        synchronized(LOCK) { if (!stopped) runLocked() }
    }

    private fun runLocked() {
        var c: MediaCodec? = null
        try {
            val (w, h) = queryDisplaySize()
            deviceWidth = w; deviceHeight = h
            val scale = if (options.maxSize <= 0) 1f else minOf(1f, options.maxSize / maxOf(w, h).toFloat())
            val vw = maxOf(16, (w * scale).toInt() and 7.inv())
            val vh = maxOf(16, (h * scale).toInt() and 7.inv())
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, vw, vh).apply {
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 2 * 1024 * 1024)
                setInteger("low-latency", 1)
            }
            c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            c.configure(format, surface, null, 0)
            c.start()
            codec = c
            onSize(vw, vh)
            val decoder = c
            thread(name = "adb-mirror-out", isDaemon = true) { drainOutput(decoder) }

            var pts = 0L
            while (!stopped) {
                val stream = connection.open("exec:screenrecord --output-format=h264 --bit-rate ${options.bitRate} --size ${vw}x$vh --time-limit 175 -")
                var nals = 0
                val head = StringBuilder()
                val splitter = NalSplitter { nal ->
                    nals++
                    val type = nal[if (nal[2].toInt() == 1) 3 else 4].toInt() and 0x1F
                    if (type == 1 || type == 5) pts += 16_667
                    queue(decoder, nal, pts, if (type == 7 || type == 8) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0)
                }
                try {
                    while (!stopped) {
                        val chunk = stream.read(2000)
                        if (chunk == null || chunk.isEmpty()) { if (stream.closed) break else continue }
                        if (nals == 0 && head.length < 300) head.append(String(chunk, 0, minOf(chunk.size, 300), Charsets.ISO_8859_1))
                        splitter.feed(chunk)
                    }
                } finally { runCatching { stream.close() } }
                if (nals == 0 && !stopped) {
                    val msg = head.toString().filter { it == '\n' || it >= ' ' }.trim()
                    onError(if (msg.isNotEmpty()) msg.take(160) else "No video from the device (screenrecord unavailable?)")
                    break
                }
            }
        } catch (e: Exception) {
            if (!stopped) onError(e.message ?: e.javaClass.simpleName)
        } finally {
            stopped = true
            runCatching { c?.stop() }
            runCatching { c?.release() }
            codec = null
        }
    }

    private fun queryDisplaySize(): Pair<Int, Int> {
        val text = try { connection.shell("wm size") } catch (_: Exception) { "" }
        val m = Regex("(Override|Physical) size: (\\d+)x(\\d+)").findAll(text).toList()
        val pick = m.firstOrNull { it.groupValues[1] == "Override" } ?: m.firstOrNull()
        return if (pick != null) pick.groupValues[2].toInt() to pick.groupValues[3].toInt() else 1280 to 720
    }

    private fun queue(c: MediaCodec, nal: ByteArray, pts: Long, flags: Int) {
        var index = -1
        var tries = 0
        while (index < 0 && !stopped && tries++ < 50) index = c.dequeueInputBuffer(10_000)
        if (index < 0) return
        val buf = c.getInputBuffer(index) ?: return
        buf.clear()
        if (nal.size > buf.capacity()) { c.queueInputBuffer(index, 0, 0, pts, 0); return }
        buf.put(nal)
        c.queueInputBuffer(index, 0, nal.size, pts, flags)
    }

    private fun drainOutput(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (!stopped) {
            try {
                val i = c.dequeueOutputBuffer(info, 10_000)
                when {
                    i >= 0 -> c.releaseOutputBuffer(i, true) // render immediately: lowest latency
                    i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = c.outputFormat
                        onSize(f.getInteger(MediaFormat.KEY_WIDTH), f.getInteger(MediaFormat.KEY_HEIGHT))
                    }
                }
            } catch (_: Exception) { break }
        }
    }

    /** Splits an Annex-B byte stream into NAL units (each keeps its start code). */
    private class NalSplitter(private val onNal: (ByteArray) -> Unit) {
        private var buf = ByteArray(1 shl 20)
        private var len = 0

        fun feed(data: ByteArray) {
            if (len + data.size > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, len + data.size))
            System.arraycopy(data, 0, buf, len, data.size)
            len += data.size
            val starts = ArrayList<Int>()
            var i = 0
            while (i + 2 < len) {
                if (buf[i].toInt() == 0 && buf[i + 1].toInt() == 0 && buf[i + 2].toInt() == 1) {
                    starts += if (i > 0 && buf[i - 1].toInt() == 0) i - 1 else i
                    i += 3
                } else i++
            }
            if (starts.size < 2) return
            // Every NAL closed by a following start code is complete; keep the last, unfinished one.
            for (k in 0 until starts.size - 1) emit(starts[k], starts[k + 1])
            val last = starts.last()
            System.arraycopy(buf, last, buf, 0, len - last)
            len -= last
        }

        private fun emit(s: Int, e: Int) {
            val nal = buf.copyOfRange(s, e)
            // normalise to a 4-byte start code for the decoder
            val out = if (nal[2].toInt() == 1) byteArrayOf(0) + nal else nal
            onNal(out)
        }
    }

    private companion object {
        val LOCK = Any()
    }
}

package com.min.adb.iconhelper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Headless helper. Builds ONE icons.zip in Android/data/<this package>/files/<dir>:
 *  - <pkg>.webp for every app whose fingerprint (version + update time + APK size) changed since the last run
 *  - manifest.tsv: pkg<TAB>fingerprint<TAB>label for every installed app (label empty if unchanged)
 * DONE marks a complete run, ERROR (with a message) a failed one.
 * Extras: dir (run folder), token (caller's cache token), next (token to store after this run).
 * Only when token matches the one stored by the previous run are unchanged apps skipped.
 */
class DumpReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val dirName = intent.getStringExtra("dir")?.takeIf { NAME.matches(it) } ?: "adb_icons"
        val token = intent.getStringExtra("token")?.takeIf { NAME.matches(it) }.orEmpty()
        val next = intent.getStringExtra("next")?.takeIf { NAME.matches(it) }.orEmpty()
        val pending = goAsync()
        Thread {
            val base = context.getExternalFilesDir(null) ?: context.filesDir
            try {
                dumpAll(context, base, dirName, token, next)
            } catch (e: Throwable) {
                runCatching {
                    val dir = File(base, dirName).apply { mkdirs() }
                    File(dir, "ERROR").writeText(e.message ?: e.javaClass.simpleName)
                }
            } finally {
                pending.finish()
            }
        }.start()
    }

    private class Item(val pkg: String, val fp: String, val label: String, val webp: ByteArray?)

    private fun dumpAll(context: Context, base: File, dirName: String, token: String, next: String) {
        val pm = context.packageManager
        base.listFiles()?.filter { it.isDirectory && it.name != dirName }?.forEach { it.deleteRecursively() }
        val dir = File(base, dirName)
        dir.deleteRecursively()
        if (!dir.mkdirs()) throw IllegalStateException("cannot create ${dir.path}")

        val stateFile = File(base, "state.tsv")
        val previous = readState(stateFile, token)

        val pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors().coerceAtLeast(2))
        val items = ArrayList<Item>()
        try {
            val futures = pm.getInstalledPackages(0).mapNotNull { info ->
                val app = info.applicationInfo ?: return@mapNotNull null
                pool.submit(Callable<Item?> {
                    try {
                        val fp = fingerprint(info, app.sourceDir)
                        if (previous[info.packageName] == fp) {
                            Item(info.packageName, fp, "", null)
                        } else {
                            val webp = encode(render(pm.getApplicationIcon(app), ICON_SIZE))
                            // Tab-separated: labels may contain commas.
                            val label = pm.getApplicationLabel(app).toString().replace(Regex("[\\t\\r\\n]"), " ")
                            Item(info.packageName, fp, label, webp)
                        }
                    } catch (_: Exception) {
                        null // Skip apps whose icon or label can't be loaded.
                    }
                })
            }
            for (f in futures) f.get()?.let { items.add(it) }
        } finally {
            pool.shutdownNow()
        }

        ZipOutputStream(File(dir, "icons.zip").outputStream().buffered()).use { zip ->
            zip.setLevel(Deflater.BEST_SPEED)
            val manifest = StringBuilder()
            for (item in items) {
                if (item.webp != null) {
                    zip.putNextEntry(ZipEntry("${item.pkg}.webp"))
                    zip.write(item.webp)
                    zip.closeEntry()
                }
                manifest.append(item.pkg).append('\t').append(item.fp).append('\t').append(item.label).append('\n')
            }
            zip.putNextEntry(ZipEntry("manifest.tsv"))
            zip.write(manifest.toString().toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }

        if (next.isNotEmpty()) writeState(stateFile, next, items)
        // Written last: its presence means icons.zip above is complete.
        File(dir, "DONE").writeText("ok")
    }

    /** State is trusted only if the caller proves its cache matches via the same token. */
    private fun readState(file: File, token: String): Map<String, String> {
        if (token.isEmpty() || !file.isFile) return emptyMap()
        val lines = runCatching { file.readLines() }.getOrNull() ?: return emptyMap()
        if (lines.firstOrNull() != token) return emptyMap()
        val out = HashMap<String, String>()
        for (line in lines.drop(1)) {
            val i = line.indexOf('\t')
            if (i > 0) out[line.substring(0, i)] = line.substring(i + 1)
        }
        return out
    }

    private fun writeState(file: File, token: String, items: List<Item>) {
        val sb = StringBuilder(token).append('\n')
        for (item in items) sb.append(item.pkg).append('\t').append(item.fp).append('\n')
        val tmp = File(file.parentFile, "state.tmp")
        tmp.writeText(sb.toString())
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    /** Same signals PackageManager itself uses to notice an update; no hashing of APK contents. */
    private fun fingerprint(info: PackageInfo, sourceDir: String?): String {
        val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        val size = sourceDir?.let { File(it).length() } ?: 0L
        return "$code.${info.lastUpdateTime}.$size"
    }

    private fun render(drawable: Drawable, size: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(Canvas(bitmap))
        return bitmap
    }

    private fun encode(bitmap: Bitmap): ByteArray {
        val format = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY
        else Bitmap.CompressFormat.WEBP
        val out = ByteArrayOutputStream(4096)
        bitmap.compress(format, WEBP_QUALITY, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    private companion object {
        val NAME = Regex("[A-Za-z0-9_]+")
        const val ICON_SIZE = 96
        const val WEBP_QUALITY = 90
    }
}

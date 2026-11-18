package com.min.adb.iconhelper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Headless helper: dumps every app icon as a small PNG plus manifest.tsv (package<TAB>label) into
 * Android/data/<this package>/files/<dir>. DONE marks a complete run, ERROR (with a message) a failed one.
 */
class DumpReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val dirName = intent.getStringExtra("dir")?.takeIf { NAME.matches(it) } ?: "adb_icons"
        val pending = goAsync()
        Thread {
            val base = context.getExternalFilesDir(null) ?: context.filesDir
            try {
                dumpAll(context, base, dirName)
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

    private fun dumpAll(context: Context, base: File, dirName: String) {
        val pm = context.packageManager
        base.listFiles()?.filter { it.isDirectory && it.name != dirName }?.forEach { it.deleteRecursively() }
        val dir = File(base, dirName)
        dir.deleteRecursively()
        if (!dir.mkdirs()) throw IllegalStateException("cannot create ${dir.path}")

        val rows = ConcurrentLinkedQueue<String>()
        val pool = Executors.newFixedThreadPool(THREADS)
        for (app in pm.getInstalledApplications(0)) {
            pool.execute {
                try {
                    val bitmap = render(pm.getApplicationIcon(app), ICON_SIZE)
                    File(dir, "${app.packageName}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                    // Tab-separated: labels may contain commas.
                    val label = pm.getApplicationLabel(app).toString().replace(Regex("[\\t\\r\\n]"), " ")
                    rows.add("${app.packageName}\t$label")
                } catch (_: Exception) {
                    // Skip apps whose icon or label can't be loaded.
                }
            }
        }
        pool.shutdown()
        if (!pool.awaitTermination(60, TimeUnit.SECONDS)) {
            pool.shutdownNow()
            throw IllegalStateException("timed out while rendering icons")
        }
        File(dir, "manifest.tsv").writeText(rows.joinToString("\n", postfix = "\n"))
        // Written last: its presence means every PNG above is complete.
        File(dir, "DONE").writeText("ok")
    }

    private fun render(drawable: Drawable, size: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(Canvas(bitmap))
        return bitmap
    }

    private companion object {
        val NAME = Regex("[A-Za-z0-9_]+")
        const val ICON_SIZE = 108
        const val THREADS = 4
    }
}

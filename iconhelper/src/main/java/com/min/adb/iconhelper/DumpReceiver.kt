package com.min.adb.iconhelper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import java.io.File

/**
 * Runs entirely on the TV. This is the "stub" app idea: instead of the remote pulling every APK
 * over ADB and parsing it just to read one icon, this tiny helper sits on-device and asks the
 * TV's own PackageManager directly - which is instant, since it never has to touch an APK file.
 *
 * Writes one small PNG per installed app plus a manifest.csv (packageName,label) into
 * Android/data/<this package>/files/<dir>, then drops a DONE sentinel so the caller on the phone/controller side
 * knows when it's safe to tar + pull the whole folder in one shot.
 */
class DumpReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // goAsync() keeps the receiver (and process) alive past onReceive() returning, since the
        // dump can take a couple of seconds for a large app list.
        val pending = goAsync()
        Thread {
            try {
                dumpAll(context, intent.getStringExtra("dir")?.takeIf { it.matches(Regex("[A-Za-z0-9_]+")) } ?: "adb_icons")
            } catch (e: Exception) {
                // Best effort - whatever got written before the failure is still usable.
            } finally {
                pending.finish()
            }
        }.start()
    }

    private fun dumpAll(context: Context, dirName: String) {
        val pm = context.packageManager
        // App-specific external storage (/sdcard/Android/data/<this package>/files): our own data
        // dir, so no storage permission is needed, and adb's shell user can still read it.
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        base.listFiles()?.filter { it.isDirectory && it.name != dirName }?.forEach { it.deleteRecursively() } // stale runs
        val dir = File(base, dirName)
        dir.deleteRecursively()
        dir.mkdirs()

        val manifest = StringBuilder()
        for (app in pm.getInstalledApplications(0)) {
            try {
                val bitmap = drawableToBitmap(pm.getApplicationIcon(app), ICON_SIZE)
                File(dir, "${app.packageName}.png").outputStream().use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
                }
                val label = pm.getApplicationLabel(app).toString()
                    .replace(",", " ").replace("\n", " ").replace("\r", " ")
                manifest.append(app.packageName).append(',').append(label).append('\n')
            } catch (e: Exception) {
                // Skip apps whose icon/label can't be loaded rather than aborting the whole dump.
            }
        }
        File(dir, "manifest.csv").writeText(manifest.toString())
        // Written last, on purpose: its presence is the signal that every PNG above is complete.
        File(dir, "DONE").writeText("ok")
    }

    private fun drawableToBitmap(drawable: Drawable, size: Int): Bitmap {
        if (drawable is BitmapDrawable) {
            return Bitmap.createScaledBitmap(drawable.bitmap, size, size, true)
        }
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(canvas)
        return bitmap
    }

    companion object {
        // Small on purpose: this is a list-row icon, not a launcher icon, and keeping it small
        // keeps the whole tar tiny (and so the final pull fast).
        private const val ICON_SIZE = 108
    }
}

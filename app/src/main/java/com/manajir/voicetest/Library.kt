package com.manajir.voicetest

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream

/** Audio files imported by the user, stored privately inside the app. */
object Library {

    @Volatile var version = 0

    private fun dir(c: Context): File {
        val d = File(c.filesDir, "library")
        if (!d.exists()) d.mkdirs()
        return d
    }

    fun list(c: Context): List<File> {
        val files = dir(c).listFiles() ?: return emptyList()
        return files.filter { it.isFile }.sortedBy { it.name.lowercase() }
    }

    fun displayName(f: File): String {
        val n = f.name
        return if (n.contains('.')) n.substringBeforeLast('.') else n
    }

    fun import(c: Context, uri: Uri): Boolean {
        try {
            var name = "audio"
            c.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cur ->
                if (cur.moveToFirst()) {
                    val n = cur.getString(0)
                    if (n != null) name = n
                }
            }
            name = name.replace(Regex("[^\\p{L}\\p{N}._ -]"), "_")
            val base = if (name.contains('.')) name.substringBeforeLast('.') else name
            val ext = if (name.contains('.')) "." + name.substringAfterLast('.') else ""
            var target = File(dir(c), name)
            var n = 1
            while (target.exists()) {
                target = File(dir(c), base + "_" + n + ext)
                n++
            }
            val input = c.contentResolver.openInputStream(uri) ?: return false
            input.use { ins ->
                FileOutputStream(target).use { out -> ins.copyTo(out) }
            }
            version++
            return true
        } catch (e: Throwable) {
            Engine.log(Lang.get(R.string.import_error, e.toString()))
            return false
        }
    }

    fun delete(f: File) {
        if (Player.currentFile == f) Player.stop()
        f.delete()
        version++
        Engine.notifyChange()
    }
}

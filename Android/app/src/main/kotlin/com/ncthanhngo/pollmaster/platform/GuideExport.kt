package com.ncthanhngo.pollmaster.platform

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException

/** Shares / saves the bundled AI template-writing guide. */
object GuideExport {
    const val ASSET = "huong-dan-tao-mau-bang-ai.md"
    val canSaveToDownloads: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    fun share(context: Context, title: String) {
        val dir = File(context.cacheDir, "guide").apply { mkdirs() }
        val file = File(dir, ASSET)
        context.assets.open(ASSET).use { input -> file.outputStream().use { input.copyTo(it) } }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        fun send(mime: String) = Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = ClipData.newRawUri(null, uri)
            },
            title,
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(send("text/markdown"))
        } catch (_: ActivityNotFoundException) {
            context.startActivity(send("text/plain"))
        }
    }

    /** Saves into the public Downloads folder (API 29+). */
    fun saveToDownloads(context: Context) {
        if (!canSaveToDownloads) throw IOException("Cần Android 10 trở lên")
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, ASSET)
            put(MediaStore.Downloads.MIME_TYPE, "text/markdown")
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IOException("Không tạo được tệp")
        try {
            val out = resolver.openOutputStream(uri) ?: throw IOException("Không ghi được tệp")
            out.use { o -> context.assets.open(ASSET).use { it.copyTo(o) } }
        } catch (e: IOException) {
            resolver.delete(uri, null, null)
            throw e
        }
    }
}

package com.ncthanhngo.pollmaster.export

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.ncthanhngo.pollmaster.core.export.DocImageData
import com.ncthanhngo.pollmaster.core.export.DocImageLoader
import java.io.ByteArrayOutputStream
import java.io.File

/** Loads record images for the Word exporter, downscaling large photos to keep the file small. */
class RecordImageLoader(private val recordDir: File) : DocImageLoader {
    override fun load(path: String): DocImageData? {
        val file = File(recordDir, path)
        if (!file.isFile) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val ext = file.extension.lowercase().let { if (it == "jpeg") "jpg" else it }
        val longEdge = maxOf(bounds.outWidth, bounds.outHeight)
        if (file.length() <= RAW_LIMIT && longEdge <= MAX_EDGE) {
            return DocImageData(file.readBytes(), bounds.outWidth, bounds.outHeight, ext)
        }
        val bmp = decodeScaled(file, MAX_EDGE) ?: return null
        val out = ByteArrayOutputStream()
        val png = ext == "png"
        bmp.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 82, out)
        return DocImageData(out.toByteArray(), bmp.width, bmp.height, if (png) "png" else "jpg")
    }

    private companion object {
        const val RAW_LIMIT = 600_000L
        const val MAX_EDGE = 1280
    }
}

/** Decodes [file] so that its long edge is about [maxEdge] pixels. */
fun decodeScaled(file: File, maxEdge: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    val longEdge = maxOf(bounds.outWidth, bounds.outHeight)
    if (longEdge <= 0) return null
    var sample = 1
    while (longEdge / (sample * 2) >= maxEdge) sample *= 2
    val decoded = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
    val scale = maxEdge.toFloat() / maxOf(decoded.width, decoded.height)
    if (scale >= 1f) return decoded
    return Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt().coerceAtLeast(1), (decoded.height * scale).toInt().coerceAtLeast(1), true)
}

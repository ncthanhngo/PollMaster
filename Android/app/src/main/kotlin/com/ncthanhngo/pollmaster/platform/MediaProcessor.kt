package com.ncthanhngo.pollmaster.platform

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import com.ncthanhngo.pollmaster.core.model.Media
import com.ncthanhngo.pollmaster.data.Settings
import java.io.File
import java.io.IOException
import java.util.UUID

/** Creates capture targets and turns captured/picked media into files in a record's media folder. */
class MediaProcessor(private val context: Context, private val settings: Settings) {
    private val captureDir get() = File(context.cacheDir, "capture").also { it.mkdirs() }

    fun newCaptureFile(extension: String): File = File(captureDir, "${UUID.randomUUID()}.$extension")

    fun uriFor(file: File): Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    /** Lowercase file extension of a picked [uri] (from its display name, else its MIME type), or [fallback]. */
    fun extensionOf(uri: Uri, fallback: String): String {
        val fromName = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.substringAfterLast('.', "") else null
        }
        val ext = fromName?.takeIf { it.isNotEmpty() }
            ?: context.contentResolver.getType(uri)?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
        return ext?.lowercase()?.takeIf { it.isNotEmpty() } ?: fallback
    }

    /** Copies a picked content [uri] into the capture folder. */
    fun copyToCapture(uri: Uri, extension: String): File {
        val target = newCaptureFile(extension)
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Không mở được tệp đã chọn")
        input.use { src -> target.outputStream().use { src.copyTo(it) } }
        return target
    }

    /**
     * Rotates per EXIF, limits the long edge and re-encodes as JPEG at the configured quality into
     * [dest]. Deletes [src]. Returns media metadata (location may be null).
     */
    fun finishImage(src: File, dest: File, location: android.location.Location?, takenAt: String): Media {
        try {
            val quality = settings.photoQuality.value
            val bitmap = decode(src, quality.maxEdge)
            dest.parentFile?.mkdirs()
            dest.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, quality.jpegQuality, it) }
            bitmap.recycle()
        } finally {
            src.delete()
        }
        return Media("media/${dest.name}", takenAt, location?.latitude, location?.longitude)
    }

    /** Centre square crop of a picked image, [side] px, as JPEG (project avatar). Deletes [src]. */
    fun squareJpeg(src: File, side: Int = 256): ByteArray {
        try {
            val full = decode(src, side * 4)
            val edge = minOf(full.width, full.height)
            val crop = Bitmap.createBitmap(full, (full.width - edge) / 2, (full.height - edge) / 2, edge, edge)
            val square = Bitmap.createScaledBitmap(crop, side, side, true)
            return java.io.ByteArrayOutputStream().also { square.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
        } finally {
            src.delete()
        }
    }

    /** Moves a video into the record media folder. */
    fun finishVideo(src: File, dest: File, location: android.location.Location?, takenAt: String): Media {
        dest.parentFile?.mkdirs()
        src.copyTo(dest, overwrite = true)
        src.delete()
        return Media("media/${dest.name}", takenAt, location?.latitude, location?.longitude)
    }

    private fun decode(file: File, maxEdge: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        val longEdge = maxOf(bounds.outWidth, bounds.outHeight)
        if (longEdge <= 0) throw IOException("Ảnh không hợp lệ")
        var sample = 1
        while (longEdge / (sample * 2) >= maxEdge) sample *= 2
        val raw = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IOException("Không giải mã được ảnh")
        val matrix = Matrix()
        when (ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
        }
        val scale = maxEdge.toFloat() / maxOf(raw.width, raw.height)
        if (scale < 1f) matrix.postScale(scale, scale)
        if (matrix.isIdentity) return raw
        return Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true).also { if (it !== raw) raw.recycle() }
    }
}

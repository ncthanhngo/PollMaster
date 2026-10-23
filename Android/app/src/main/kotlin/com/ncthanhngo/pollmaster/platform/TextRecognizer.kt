package com.ncthanhngo.pollmaster.platform

import android.graphics.BitmapFactory
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** On-device text recognition (ML Kit Latin model bundled in the app; keeps Vietnamese diacritics reasonably). */
object TextRecognizer {
    private const val MAX_EDGE = 2048

    /** Text lines found in [files], in reading order, blank and duplicate lines removed. */
    suspend fun lines(files: List<File>): List<String> {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val out = LinkedHashSet<String>()
            for (file in files) {
                val bitmap = decode(file) ?: continue
                val text = suspendCancellableCoroutine { cont ->
                    recognizer.process(InputImage.fromBitmap(bitmap, 0))
                        .addOnSuccessListener { cont.resume(it) }
                        .addOnFailureListener { cont.resumeWithException(it) }
                }
                text.textBlocks.flatMap { it.lines }.map { it.text.trim() }.filter { it.isNotEmpty() }.forEach(out::add)
            }
            return out.toList()
        } finally {
            recognizer.close()
        }
    }

    private fun decode(file: File): android.graphics.Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_EDGE) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}

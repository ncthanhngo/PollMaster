package com.ncthanhngo.pollmaster.core.editor

import com.ncthanhngo.pollmaster.core.model.Record
import com.ncthanhngo.pollmaster.core.model.Template
import com.ncthanhngo.pollmaster.core.model.recordCode
import com.ncthanhngo.pollmaster.core.model.sanitizeFileName

const val DEFAULT_MEDIA_PATTERN = "{recordCode}_{fieldId}_{seq}"

/** File name (with extension) for media number [seq] of [fieldKey], following the template pattern. */
fun mediaFileName(template: Template, record: Record, fieldKey: String, seq: Int, extension: String): String {
    val pattern = template.mediaNamePattern ?: DEFAULT_MEDIA_PATTERN
    val code = template.recordCode(record) ?: record.id
    val base = pattern
        .replace("{recordCode}", code)
        .replace("{fieldId}", sanitizeFileName(fieldKey))
        .replace("{seq}", String.format(java.util.Locale.ROOT, "%02d", seq))
    return "${sanitizeFileName(base)}.$extension"
}

/** First free media name in [taken] (existing file names). */
fun nextMediaName(template: Template, record: Record, fieldKey: String, extension: String, taken: Set<String>): String {
    var seq = 1
    while (true) {
        val name = mediaFileName(template, record, fieldKey, seq, extension)
        if (name !in taken) return name
        seq++
    }
}

/** Signature image path inside the record folder: `media/sig_{signatureId}.png` (id made file-safe). */
fun signatureFileName(signatureId: String): String = "sig_${sanitizeFileName(signatureId)}.png"

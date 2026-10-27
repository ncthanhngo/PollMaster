package com.ncthanhngo.pollmaster.core.export

import com.ncthanhngo.pollmaster.core.format.parseInstant
import com.ncthanhngo.pollmaster.core.format.recordTitle
import com.ncthanhngo.pollmaster.core.model.Record
import com.ncthanhngo.pollmaster.core.model.Template
import com.ncthanhngo.pollmaster.core.model.rawRecordCode

/** "Gửi lên ERP" (schema 9.6): the text fields of the multipart request and the "changed since upload" rule. */
object ErpUpload {
    /** Form fields in contract order; optional ones are left out when empty. The `pdf` and `zip` file parts are added by the client. */
    fun fields(template: Template, record: Record): List<Pair<String, String>> = buildList {
        add("record_id" to record.id)
        template.rawRecordCode(record)?.let { add("record_code" to it) }
        add("title" to recordTitle(template, record))
        add("template_id" to template.id)
        add("template_version" to template.version)
        add("template_title" to template.title)
        add("status" to if (record.isCompleted) Record.STATUS_COMPLETED else Record.STATUS_DRAFT)
        if (record.createdAt.isNotEmpty()) add("recorded_at" to record.createdAt)
        record.completedAt?.takeIf { it.isNotEmpty() }?.let { add("completed_at" to it) }
    }

    /** True when the record was edited after its last upload (never uploaded: false, there is nothing to compare). */
    fun hasUnsentChanges(record: Record): Boolean {
        val sent = parseInstant(record.erpUploadedAt) ?: return false
        val updated = parseInstant(record.updatedAt) ?: return false
        return updated.isAfter(sent)
    }

    /**
     * After the upload: the stamp (time taken before the files were made) is written unless the record was deleted or moved to
     * another project meanwhile; an edit made during the upload stays newer than the stamp and so shows as unsent.
     */
    fun shouldStamp(uploaded: Record, current: Record?): Boolean = current != null && current.projectId == uploaded.projectId

    const val MAX_PDF_BYTES = 20L * 1024 * 1024
    const val MAX_ZIP_BYTES = 75L * 1024 * 1024

    /** Message for the first file over the server limit (PDF checked first), or null when both fit. */
    fun sizeError(pdfBytes: Long, zipBytes: Long): String? = when {
        pdfBytes > MAX_PDF_BYTES -> "Tệp PDF ${mb(pdfBytes)} MB vượt giới hạn 20 MB của ERP."
        zipBytes > MAX_ZIP_BYTES -> "Tệp dữ liệu (zip) ${mb(zipBytes)} MB vượt giới hạn 75 MB của ERP — bớt ảnh hoặc giảm chất lượng ảnh rồi gửi lại."
        else -> null
    }

    private fun mb(bytes: Long): String = String.format(java.util.Locale.ROOT, "%.1f", bytes / 1048576.0).replace('.', ',')

    /** Leaving the editor re-sends a record only if it was sent by hand before, changed since, and nothing else is in the way. */
    fun shouldAutoResend(record: Record, signedIn: Boolean, online: Boolean, alreadyRunning: Boolean): Boolean =
        signedIn && online && !alreadyRunning && record.erpUploadedAt != null && hasUnsentChanges(record)
}

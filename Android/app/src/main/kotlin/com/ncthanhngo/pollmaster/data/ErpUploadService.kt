package com.ncthanhngo.pollmaster.data

import com.ncthanhngo.pollmaster.core.export.ErpUpload
import com.ncthanhngo.pollmaster.core.format.nowIso
import com.ncthanhngo.pollmaster.core.storage.RecordStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** "Gửi lên ERP" (schema 9.6): makes the record PDF and zip with the export code, uploads them, stamps `erpUploadedAt`. */
class ErpUploadService(
    private val client: ErpClient,
    private val export: ExportService,
    private val store: RecordStore,
    private val templates: TemplatesRepository,
    private val records: RecordsRepository,
    /** The refresh token was refused: the session screen must go back to sign-in. */
    private val onSessionExpired: () -> Unit,
    private val scope: CoroutineScope,
    private val signedIn: () -> Boolean,
    private val online: () -> Boolean,
) {
    private val running = ConcurrentHashMap.newKeySet<String>()

    /** Leaving the editor: re-sends a record that was sent before and changed since, silently (errors keep the "Có thay đổi chưa gửi" marker). */
    fun autoResend(recordId: String) {
        val record = records.load(recordId) ?: return
        if (!ErpUpload.shouldAutoResend(record, signedIn(), online(), recordId in running)) return
        scope.launch { runCatching { upload(recordId) } }
    }

    /** Throws [ErpException] (message fit for the user) or [IllegalStateException] for a missing record, template or project. */
    suspend fun upload(recordId: String) {
        if (!running.add(recordId)) throw ErpException("Phiếu đang được gửi lên ERP, thử lại sau ít giây.")
        try { send(recordId) } finally { running.remove(recordId) }
    }

    private suspend fun send(recordId: String) {
        val startedAt = nowIso()
        val (record, template, files) = withContext(Dispatchers.IO) {
            records.flushEditor(recordId)
            val record = store.load(recordId) ?: error("Không tìm thấy phiếu")
            val template = templates.find(record.templateId, record.templateVersion) ?: error("Không tìm thấy mẫu ${record.templateId}")
            Triple(record, template, export.export(recordId, setOf(ExportFormat.PDF, ExportFormat.ZIP)))
        }
        val projectId = record.projectId ?: error("Phiếu chưa thuộc dự án nào")
        val pdf = files.first { it.extension == ExportFormat.PDF.extension }
        val zip = files.first { it.extension == ExportFormat.ZIP.extension }
        ErpUpload.sizeError(pdf.length(), zip.length())?.let { throw ErpException(it) }
        try {
            client.uploadPoll(projectId, ErpUpload.fields(template, record), pdf, zip)
        } catch (e: ErpException) {
            if (e.sessionExpired) onSessionExpired()
            throw e
        } catch (e: IOException) {
            throw ErpException("Không kết nối được máy chủ ERP. Kiểm tra mạng rồi thử lại.")
        }
        withContext(Dispatchers.IO) { records.markErpUploaded(record, startedAt) }
    }
}

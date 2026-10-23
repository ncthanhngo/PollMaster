package com.ncthanhngo.pollmaster.data

import android.content.Context
import com.ncthanhngo.pollmaster.core.export.CsvWriter
import com.ncthanhngo.pollmaster.core.export.DocxWriter
import com.ncthanhngo.pollmaster.core.export.ExportBuilder
import com.ncthanhngo.pollmaster.core.export.XlsxWriter
import com.ncthanhngo.pollmaster.core.export.ZipExporter
import com.ncthanhngo.pollmaster.core.format.fileStamp
import com.ncthanhngo.pollmaster.core.model.recordCode
import com.ncthanhngo.pollmaster.core.storage.RecordStore
import com.ncthanhngo.pollmaster.export.PdfExporter
import com.ncthanhngo.pollmaster.export.RecordImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

enum class ExportFormat(val key: String, val extension: String) {
    ZIP("zip", "zip"),
    XLSX("xlsx", "xlsx"),
    CSV("csv", "csv"),
    PDF("pdf", "pdf"),
    DOCX("docx", "docx"),
}

class ExportService(
    private val context: Context,
    private val store: RecordStore,
    private val templates: TemplatesRepository,
    private val records: RecordsRepository,
) {
    /** Creates the requested files in the cache export folder and returns them. */
    suspend fun export(recordId: String, formats: Set<ExportFormat>): List<File> = withContext(Dispatchers.IO) {
        records.flushEditor(recordId)
        val record = store.load(recordId) ?: error("Không tìm thấy phiếu")
        val template = templates.find(record.templateId, record.templateVersion) ?: error("Không tìm thấy mẫu ${record.templateId}")
        val dir = File(context.cacheDir, "exports").also { it.deleteRecursively(); it.mkdirs() }
        val base = "${template.recordCode(record) ?: record.id}_${fileStamp()}"
        val recordDir = store.dir(recordId)
        val builder = ExportBuilder(template, record)
        formats.sortedBy { it.ordinal }.map { format ->
            File(dir, "$base.${format.extension}").also { file ->
                file.outputStream().buffered().use { out ->
                    when (format) {
                        ExportFormat.ZIP -> ZipExporter.write(template, record, recordDir, out)
                        ExportFormat.XLSX -> XlsxWriter.write(builder.allSheets(), out)
                        ExportFormat.CSV -> CsvWriter.write(builder.infoSheet(), out)
                        ExportFormat.PDF -> PdfExporter(recordDir).write(builder.document(), out)
                        ExportFormat.DOCX -> DocxWriter(RecordImageLoader(recordDir)).write(builder.document(), out)
                    }
                }
            }
        }
    }
}

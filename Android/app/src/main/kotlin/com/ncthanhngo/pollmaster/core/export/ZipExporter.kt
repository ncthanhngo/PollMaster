package com.ncthanhngo.pollmaster.core.export

import com.ncthanhngo.pollmaster.core.model.Record
import com.ncthanhngo.pollmaster.core.model.Template
import com.ncthanhngo.pollmaster.core.model.recordCode
import com.ncthanhngo.pollmaster.core.project.ProjectRef
import com.ncthanhngo.pollmaster.core.storage.RecordStore
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** One record of a multi-record zip: its template, data and folder on disk. */
class ZipRecord(val template: Template, val record: Record, val dir: File)

/** "JSON + media" export: record.json, export.json and media/. */
object ZipExporter {
    fun write(template: Template, record: Record, recordDir: File, out: OutputStream, project: ProjectRef? = null) {
        ZipOutputStream(out).use { zip -> writeRecord(zip, template, record, recordDir, project, "") }
    }

    /** Every record in its own top-level folder `{recordCode|recordId}` (suffix `-2`, `-3` when names repeat). */
    fun writeMany(records: List<ZipRecord>, out: OutputStream, project: ProjectRef? = null) {
        val used = HashSet<String>()
        ZipOutputStream(out).use { zip ->
            for (r in records) {
                val base = r.template.recordCode(r.record) ?: r.record.id
                var folder = base
                var n = 2
                while (!used.add(folder.lowercase())) folder = "$base-${n++}"
                writeRecord(zip, r.template, r.record, r.dir, project, "$folder/")
            }
        }
    }

    private fun writeRecord(zip: ZipOutputStream, template: Template, record: Record, recordDir: File, project: ProjectRef?, prefix: String) {
        zip.putNextEntry(ZipEntry(prefix + "record.json"))
        zip.write(File(recordDir, RecordStore.RECORD_FILE).readBytes())
        zip.closeEntry()
        zip.putNextEntry(ZipEntry(prefix + "export.json"))
        zip.write(ExportJson.encode(template, record, project).toByteArray(Charsets.UTF_8))
        zip.closeEntry()
        File(recordDir, "media").listFiles { f -> f.isFile }.orEmpty().sortedBy { it.name }.forEach { f ->
            zip.putNextEntry(ZipEntry("${prefix}media/${f.name}"))
            f.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }
    }
}

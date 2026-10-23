package com.ncthanhngo.pollmaster.core.export

import com.ncthanhngo.pollmaster.core.model.Record
import com.ncthanhngo.pollmaster.core.model.Template
import com.ncthanhngo.pollmaster.core.storage.RecordStore
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** "JSON + media" export: record.json, export.json and media/. */
object ZipExporter {
    fun write(template: Template, record: Record, recordDir: File, out: OutputStream) {
        ZipOutputStream(out).use { zip ->
            val recordJson = File(recordDir, RecordStore.RECORD_FILE)
            zip.putNextEntry(ZipEntry("record.json"))
            zip.write(recordJson.readBytes())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("export.json"))
            zip.write(ExportJson.encode(template, record).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            File(recordDir, "media").listFiles { f -> f.isFile }.orEmpty().sortedBy { it.name }.forEach { f ->
                zip.putNextEntry(ZipEntry("media/${f.name}"))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }
}

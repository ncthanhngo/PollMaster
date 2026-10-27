package com.ncthanhngo.pollmaster.data

import android.content.Context
import com.ncthanhngo.pollmaster.core.export.ProjectSummary
import com.ncthanhngo.pollmaster.core.export.XlsxWriter
import com.ncthanhngo.pollmaster.core.export.ZipExporter
import com.ncthanhngo.pollmaster.core.export.ZipRecord
import com.ncthanhngo.pollmaster.core.format.fileStamp
import com.ncthanhngo.pollmaster.core.project.ProjectNames
import com.ncthanhngo.pollmaster.core.project.ref
import com.ncthanhngo.pollmaster.core.storage.RecordStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** "Xuất tổng hợp" of a project (schema 9.4). */
class ProjectExportService(
    private val context: Context,
    private val projects: ProjectsRepository,
    private val recordStore: RecordStore,
    private val templates: TemplatesRepository,
    private val records: RecordsRepository,
) {
    /** Number of records the summary would contain. */
    suspend fun count(projectId: String, completedOnly: Boolean): Int = withContext(Dispatchers.IO) { select(projectId, completedOnly).size }

    /** Creates `{code}_tong-hop_{stamp}.xlsx` and, with [withRecordsZip], `{code}_phieu_{stamp}.zip`. */
    suspend fun export(projectId: String, completedOnly: Boolean, withRecordsZip: Boolean): List<File> = withContext(Dispatchers.IO) {
        val project = projects.store.get(projectId) ?: error("Không tìm thấy dự án")
        records.flushAll()
        val mine = select(projectId, completedOnly)
        val entries = mine.mapNotNull { r -> templates.find(r.templateId, r.templateVersion)?.let { it to r } }
        val layouts = project.templates.mapNotNull { templates.find(it.id, it.version) }
        val dir = File(context.cacheDir, "exports").also { it.deleteRecursively(); it.mkdirs() }
        val stamp = fileStamp()
        val sheets = ProjectSummary.sheets(layouts, entries, completedOnly = false) // already filtered by select()
        val files = mutableListOf(File(dir, ProjectNames.summary(project.code, stamp)).also { f ->
            f.outputStream().buffered().use { XlsxWriter.write(sheets, it) }
        })
        if (withRecordsZip) {
            files += File(dir, ProjectNames.recordsZip(project.code, stamp)).also { f ->
                val items = entries.map { (t, r) -> ZipRecord(t, r, recordStore.dir(r.id)) }
                f.outputStream().buffered().use { ZipExporter.writeMany(items, it, project.ref()) }
            }
        }
        files
    }

    private fun select(projectId: String, completedOnly: Boolean) = recordStore.list().filter {
        it.projectId == projectId && (!completedOnly || it.isCompleted)
    }
}

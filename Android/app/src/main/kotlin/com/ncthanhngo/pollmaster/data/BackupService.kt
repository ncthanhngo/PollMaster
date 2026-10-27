package com.ncthanhngo.pollmaster.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.ncthanhngo.pollmaster.core.export.ZipExporter
import com.ncthanhngo.pollmaster.core.format.nowIso
import com.ncthanhngo.pollmaster.core.model.recordCode
import com.ncthanhngo.pollmaster.core.project.ref
import com.ncthanhngo.pollmaster.core.storage.Backup
import com.ncthanhngo.pollmaster.core.storage.RecordStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException

/** Outcome of the latest backup attempt, shown in Settings. */
data class BackupStatus(val lastOkAt: String? = null, val error: String? = null)

/** Writes "JSON + media" zips to Download/PollMaster (MediaStore) and records `backupAt` (ux-spec A). */
class BackupService(
    private val context: Context,
    private val scope: CoroutineScope,
    private val store: RecordStore,
    private val templates: TemplatesRepository,
    private val records: RecordsRepository,
    private val settings: Settings,
    private val projects: ProjectsRepository,
) {
    private val mutex = Mutex()
    private val _status = MutableStateFlow(BackupStatus())
    val status: StateFlow<BackupStatus> = _status.asStateFlow()

    /** Background backup of [recordId] when auto backup is on and the record changed since its last backup. */
    fun requestIfNeeded(recordId: String) {
        if (!settings.autoBackup.value) return
        scope.launch {
            records.flushEditor(recordId)
            val record = store.load(recordId) ?: return@launch
            if (Backup.needsBackup(record)) backup(recordId)
        }
    }

    /** Backs up every record now (Settings button); returns (done, total). */
    suspend fun backupAll(): Pair<Int, Int> {
        val ids = withContext(Dispatchers.IO) { store.list().map { it.id } }
        return ids.count { backup(it) } to ids.size
    }

    suspend fun backup(recordId: String): Boolean = mutex.withLock {
        try {
            withContext(Dispatchers.IO) { backupBlocking(recordId) }
            true
        } catch (e: Exception) { // reported in Settings, never swallowed
            _status.value = _status.value.copy(error = e.message ?: e.javaClass.simpleName)
            false
        }
    }

    private fun backupBlocking(recordId: String) {
        records.flushEditor(recordId)
        val at = nowIso()
        val record = store.load(recordId) ?: error("Không tìm thấy phiếu")
        val template = templates.find(record.templateId, record.templateVersion) ?: error("Không tìm thấy mẫu ${record.templateId}")
        val project = projects.get(record.projectId)
        val tmp = File(context.cacheDir, "backup-$recordId.zip")
        try {
            tmp.outputStream().buffered().use { ZipExporter.write(template, record, store.dir(recordId), it, project?.ref()) }
            write(Backup.fileName(template.recordCode(record), recordId, project?.code), Backup.folder(project?.code), tmp)
        } finally {
            tmp.delete()
        }
        records.markBackedUp(record, at)
        _status.value = BackupStatus(lastOkAt = at)
    }

    /** Zips the device version of [recordId] under a name of its own (never replaced by the next backup) before the server's is taken. */
    suspend fun backupConflictCopy(recordId: String): Boolean = mutex.withLock {
        try {
            withContext(Dispatchers.IO) {
                records.flushEditor(recordId)
                val record = store.load(recordId) ?: error("Không tìm thấy phiếu")
                val template = templates.find(record.templateId, record.templateVersion) ?: error("Không tìm thấy mẫu ${record.templateId}")
                val project = projects.get(record.projectId)
                val tmp = File(context.cacheDir, "conflict-$recordId.zip")
                try {
                    tmp.outputStream().buffered().use { ZipExporter.write(template, record, store.dir(recordId), it, project?.ref()) }
                    write(Backup.conflictCopyName(template.recordCode(record), recordId, project?.code,
                        java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(java.time.ZonedDateTime.now())), Backup.folder(project?.code), tmp)
                } finally { tmp.delete() }
            }
            true
        } catch (e: Exception) {
            _status.value = _status.value.copy(error = e.message ?: e.javaClass.simpleName)
            false
        }
    }

    /** Removes the backup file of [recordId] in its current project's folder (used before the record moves to another project). */
    suspend fun deleteBackup(recordId: String): Boolean = mutex.withLock {
        try {
            withContext(Dispatchers.IO) {
                records.flushEditor(recordId)
                val record = store.load(recordId) ?: return@withContext
                val template = templates.find(record.templateId, record.templateVersion) ?: return@withContext
                val project = projects.get(record.projectId)
                remove(Backup.fileName(template.recordCode(record), recordId, project?.code), Backup.folder(project?.code))
            }
            true
        } catch (e: Exception) { // reported in Settings, never swallowed
            _status.value = _status.value.copy(error = e.message ?: e.javaClass.simpleName)
            false
        }
    }

    private fun remove(name: String, folder: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            File(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), folder.removePrefix("Download/")), name).delete()
            return
        }
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        resolver.query(
            collection, arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?", arrayOf(name, "$folder/"), null,
        )?.use { c -> while (c.moveToNext()) resolver.delete(ContentUris.withAppendedId(collection, c.getLong(0)), null, null) }
    }

    private fun write(name: String, folder: String, file: File) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), folder.removePrefix("Download/"))
            if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Không tạo được thư mục $folder")
            file.copyTo(File(dir, name), overwrite = true)
            return
        }
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val relative = "$folder/"
        val existing = resolver.query(
            collection, arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?", arrayOf(name, relative), null,
        )?.use { if (it.moveToFirst()) ContentUris.withAppendedId(collection, it.getLong(0)) else null }
        val stream = existing?.let { runCatching { resolver.openOutputStream(it, "wt") }.getOrNull() }
        val out = stream ?: run {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
                put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
            }
            val uri = resolver.insert(collection, values) ?: throw IOException("Không tạo được tệp sao lưu")
            resolver.openOutputStream(uri, "wt") ?: throw IOException("Không ghi được tệp sao lưu")
        }
        out.use { o -> file.inputStream().use { it.copyTo(o) } }
    }
}

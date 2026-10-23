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
import com.ncthanhngo.pollmaster.core.storage.Backup
import com.ncthanhngo.pollmaster.core.storage.RecordStore
import kotlinx.coroutines.CoroutineScope
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
        val ids = store.list().map { it.id }
        return ids.count { backup(it) } to ids.size
    }

    suspend fun backup(recordId: String): Boolean = mutex.withLock {
        try {
            records.flushEditor(recordId)
            val at = nowIso()
            val record = store.load(recordId) ?: error("Không tìm thấy phiếu")
            val template = templates.find(record.templateId, record.templateVersion) ?: error("Không tìm thấy mẫu ${record.templateId}")
            val tmp = File(context.cacheDir, "backup-$recordId.zip")
            try {
                tmp.outputStream().buffered().use { ZipExporter.write(template, record, store.dir(recordId), it) }
                write(Backup.fileName(template.recordCode(record), recordId), tmp)
            } finally {
                tmp.delete()
            }
            records.markBackedUp(recordId, at)
            _status.value = BackupStatus(lastOkAt = at)
            true
        } catch (e: Exception) { // reported in Settings, never swallowed
            _status.value = _status.value.copy(error = e.message ?: e.javaClass.simpleName)
            false
        }
    }

    private fun write(name: String, file: File) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "PollMaster")
            if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Không tạo được thư mục ${Backup.FOLDER}")
            file.copyTo(File(dir, name), overwrite = true)
            return
        }
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val relative = "${Backup.FOLDER}/"
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

package com.ncthanhngo.pollmaster.data

import com.ncthanhngo.pollmaster.core.editor.EditorController
import com.ncthanhngo.pollmaster.core.editor.RecordFactory
import com.ncthanhngo.pollmaster.core.format.recordTitle
import com.ncthanhngo.pollmaster.core.model.Record
import com.ncthanhngo.pollmaster.core.model.Template
import com.ncthanhngo.pollmaster.core.model.allFields
import com.ncthanhngo.pollmaster.core.model.mediaItems
import com.ncthanhngo.pollmaster.core.model.recordCode
import com.ncthanhngo.pollmaster.core.storage.Backup
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import com.ncthanhngo.pollmaster.core.storage.DebouncedSaver
import com.ncthanhngo.pollmaster.core.storage.RecordStore
import com.ncthanhngo.pollmaster.core.storage.StoredRecord
import com.ncthanhngo.pollmaster.core.validation.Progress
import com.ncthanhngo.pollmaster.core.validation.Validator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

data class RecordItem(
    val record: Record,
    val template: Template?,
    val title: String,
    val progress: Progress,
    /** First photo of the first photo field that has one (list thumbnail), or null. */
    val thumbnail: File? = null,
    /** `{template id} · {record code}` (second line of a list row). */
    val subtitle: String = "",
) {
    val needsBackup: Boolean get() = Backup.needsBackup(record)
}

private const val BACKUP_INTERVAL_MS = 15 * 60 * 1000L

class RecordsRepository(
    private val scope: CoroutineScope,
    private val store: RecordStore,
    private val templates: TemplatesRepository,
    private val settings: Settings,
) {
    private val _items = MutableStateFlow<List<RecordItem>>(emptyList())
    val items: StateFlow<List<RecordItem>> = _items.asStateFlow()

    private val _broken = MutableStateFlow<List<StoredRecord.Broken>>(emptyList())
    /** Record folders whose record.json cannot be read. */
    val broken: StateFlow<List<StoredRecord.Broken>> = _broken.asStateFlow()

    private val _saveError = MutableStateFlow<String?>(null)
    val saveError: StateFlow<String?> = _saveError.asStateFlow()

    private val editors = HashMap<String, EditorController>()
    private val tickers = HashMap<String, Job>()

    /** Ids of records currently open in an editor (candidates for the "app goes to background" backup). */
    fun openIds(): List<String> = editors.keys.toList()

    /** Stores the time of a finished backup without touching `updatedAt`. */
    fun markBackedUp(recordId: String, at: String) {
        val editor = editors[recordId]
        if (editor != null) {
            editor.markBackedUp(at)
            editor.flush()
        } else {
            store.load(recordId)?.let { store.save(it.copy(backupAt = at)) }
        }
    }

    /** Called once per 15 minutes while a record is open; set by [AppContainer]. */
    var onBackupTick: (String) -> Unit = {}

    suspend fun refresh() {
        val stored = withContext(Dispatchers.IO) { store.listAll() }
        _broken.value = stored.filterIsInstance<StoredRecord.Broken>()
        _items.value = withContext(Dispatchers.IO) {
            stored.filterIsInstance<StoredRecord.Loaded>().map { it.record }.map { r ->
                val t = templates.find(r.templateId, r.templateVersion)
                RecordItem(
                    r, t,
                    t?.let { recordTitle(it, r) } ?: r.templateId,
                    t?.let { Validator(it).requiredProgress(r) } ?: Progress(0, 0),
                    thumbnail = t?.let { thumbnailOf(it, r) },
                    subtitle = listOfNotNull(r.templateId, t?.recordCode(r)).joinToString(" · "),
                )
            }
        }
    }

    private fun thumbnailOf(template: Template, record: Record): File? {
        val photo = template.allFields().filter { it.type == "photo" }
            .firstNotNullOfOrNull { f -> record.values[f.id]?.mediaItems()?.firstOrNull() } ?: return null
        return File(store.dir(record.id), photo.file).takeIf { it.isFile }
    }

    suspend fun create(template: Template): Record = withContext(Dispatchers.IO) {
        RecordFactory.create(template, settings.surveyorName.value).also { store.save(it) }
    }

    suspend fun duplicate(item: RecordItem): Record? = withContext(Dispatchers.IO) {
        val t = item.template ?: return@withContext null
        flushEditor(item.record.id)
        val source = store.load(item.record.id) ?: return@withContext null
        RecordFactory.duplicate(t, source).also { store.save(it) }
    }

    suspend fun delete(recordId: String) = withContext(Dispatchers.IO) {
        tickers.remove(recordId)?.cancel()
        editors.remove(recordId)?.flush()
        store.delete(recordId)
    }

    /** Controller for editing [recordId]; cached until [release]. Null if record or template is missing. */
    fun editor(recordId: String): EditorController? {
        editors[recordId]?.let { return it }
        val record = store.load(recordId) ?: return null
        val template = templates.find(record.templateId, record.templateVersion) ?: return null
        lateinit var controller: EditorController
        val saver = DebouncedSaver(scope, 500, onError = { _saveError.value = it.message ?: "Lỗi ghi tệp" }) {
            store.save(it)
            controller.noteSaved()
        }
        controller = EditorController(template, record, saver)
        editors[recordId] = controller
        tickers[recordId] = scope.launch {
            while (true) {
                delay(BACKUP_INTERVAL_MS)
                onBackupTick(recordId)
            }
        }
        return controller
    }

    /** Current stored state of a record (flushing its open editor first). */
    fun load(recordId: String): Record? {
        editors[recordId]?.flush()
        return store.load(recordId)
    }

    fun release(recordId: String) {
        tickers.remove(recordId)?.cancel()
        editors.remove(recordId)?.flush()
    }

    fun flushEditor(recordId: String) {
        editors[recordId]?.flush()
    }

    fun flushAll() = editors.values.forEach { it.flush() }

    fun clearSaveError() {
        _saveError.value = null
    }

    fun mediaDir(recordId: String) = store.mediaDir(recordId)
}

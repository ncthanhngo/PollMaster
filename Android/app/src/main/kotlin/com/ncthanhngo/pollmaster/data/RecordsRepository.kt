package com.ncthanhngo.pollmaster.data

import com.ncthanhngo.pollmaster.core.editor.EditorController
import com.ncthanhngo.pollmaster.core.editor.RecordCodes
import com.ncthanhngo.pollmaster.core.editor.RecordFactory
import com.ncthanhngo.pollmaster.core.export.ErpUpload
import com.ncthanhngo.pollmaster.core.format.recordTitle
import com.ncthanhngo.pollmaster.core.format.nowIso
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
import java.util.concurrent.ConcurrentHashMap
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
    /** Owning project. */
    val projectId: String? = record.projectId,
) {
    val needsBackup: Boolean get() = Backup.needsBackup(record)
}

private const val BACKUP_INTERVAL_MS = 15 * 60 * 1000L

class RecordsRepository(
    private val scope: CoroutineScope,
    private val store: RecordStore,
    private val templates: TemplatesRepository,
    private val settings: Settings,
    /** Ids of the ERP projects the account sees: records of other projects stay on the device, hidden. */
    private val visibleProjects: () -> Set<String> = { emptySet() },
) {
    private val _items = MutableStateFlow<List<RecordItem>>(emptyList())
    val items: StateFlow<List<RecordItem>> = _items.asStateFlow()

    private val _broken = MutableStateFlow<List<StoredRecord.Broken>>(emptyList())
    /** Record folders whose record.json cannot be read. */
    val broken: StateFlow<List<StoredRecord.Broken>> = _broken.asStateFlow()

    private val _saveError = MutableStateFlow<String?>(null)
    val saveError: StateFlow<String?> = _saveError.asStateFlow()

    private val editors = ConcurrentHashMap<String, EditorController>()
    private val tickers = ConcurrentHashMap<String, Job>()

    /** Ids of records currently open in an editor (candidates for the "app goes to background" backup). */
    fun openIds(): List<String> = editors.keys.toList()

    /**
     * Stores the time of a finished backup of [backedUp] without touching `updatedAt`, unless the record changed, moved or was
     * deleted meanwhile ([Backup.shouldStamp]); returns whether it was stamped.
     */
    fun markBackedUp(backedUp: Record, at: String): Boolean {
        val editor = editors[backedUp.id]
        if (editor != null) {
            if (!Backup.shouldStamp(backedUp, editor.record.value)) return false
            editor.markBackedUp(at)
            editor.flush()
            return true
        }
        val current = store.load(backedUp.id)
        if (current == null || !Backup.shouldStamp(backedUp, current)) return false
        store.save(current.copy(backupAt = at))
        return true
    }

    /** Stores the time of a finished ERP upload of [uploaded] without touching `updatedAt`, unless the record was deleted or moved meanwhile. */
    fun markErpUploaded(uploaded: Record, at: String) {
        val editor = editors[uploaded.id]
        if (editor != null) {
            if (!ErpUpload.shouldStamp(uploaded, editor.record.value)) return
            editor.markErpUploaded(at)
            editor.flush()
            return
        }
        val current = store.load(uploaded.id)
        if (current != null && ErpUpload.shouldStamp(uploaded, current)) store.save(current.copy(erpUploadedAt = at))
    }

    /** Called once per 15 minutes while a record is open; set by [AppContainer]. */
    var onBackupTick: (String) -> Unit = {}

    private var lastPrint: List<Triple<String, Long, Long>>? = null
    private var lastTemplates: List<Template>? = null
    private var lastVisible: Set<String>? = null

    /** Re-reads the list when record files, templates or visible projects changed since the last call; screens call this freely. */
    suspend fun refresh() {
        val templateList = templates.templates.value
        val visible = visibleProjects()
        val (print, stored) = withContext(Dispatchers.IO) {
            val print = store.fingerprint()
            if (print == lastPrint && templateList === lastTemplates && visible == lastVisible) return@withContext print to null
            print to store.listAll()
        }
        if (stored == null) return
        val items = withContext(Dispatchers.IO) {
            stored.filterIsInstance<StoredRecord.Loaded>().map { it.record }.filter { it.projectId in visible }.map { r ->
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
        _broken.value = stored.filterIsInstance<StoredRecord.Broken>()
        _items.value = items
        lastPrint = print; lastTemplates = templateList; lastVisible = visible
    }

    private fun thumbnailOf(template: Template, record: Record): File? {
        val photo = template.allFields().filter { it.type == "photo" }
            .firstNotNullOfOrNull { f -> record.values[f.id]?.mediaItems()?.firstOrNull() } ?: return null
        return File(store.dir(record.id), photo.file).takeIf { it.isFile }
    }

    suspend fun create(template: Template, projectId: String?): Record = withContext(Dispatchers.IO) {
        val existing = store.listAll().filterIsInstance<StoredRecord.Loaded>().map { it.record }
            .filter { it.projectId == projectId } // codes are numbered per project
        RecordFactory.create(template, settings.surveyorName.value, recordCode = RecordCodes.propose(template, existing), projectId = projectId)
            .also { store.save(it) }
    }

    /** Moves a record to another project; the record keeps its template, values and media. */
    suspend fun move(recordId: String, projectId: String) = withContext(Dispatchers.IO) {
        release(recordId)
        store.load(recordId)?.let { store.save(it.copy(projectId = projectId, updatedAt = nowIso(), backupAt = null, erpUploadedAt = null)) } // old backup is removed by the caller; a new one is due
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

    /** Closes every open editor, saving first (sign-out). */
    fun releaseAll() {
        tickers.values.forEach { it.cancel() }
        tickers.clear()
        editors.values.forEach { it.flush() }
        editors.clear()
    }

    /** Closes the open editors of records on template [id] [version] (saving first): the template file was replaced under them. */
    fun releaseUsing(id: String, version: String) {
        editors.filterValues { it.template.id == id && it.template.version == version }.keys.forEach(::release)
    }

    fun release(recordId: String) {
        tickers.remove(recordId)?.cancel()
        editors.remove(recordId)?.flush()
    }

    fun flushEditor(recordId: String) {
        editors[recordId]?.flush()
    }

    fun flushAll() = editors.values.forEach { it.flush() }

    /** Drops every open editor without saving (their records were just wiped). */
    fun discardAll() {
        tickers.values.forEach { it.cancel() }
        tickers.clear()
        editors.values.forEach { it.discard() }
        editors.clear()
    }

    fun clearSaveError() {
        _saveError.value = null
    }

    fun mediaDir(recordId: String) = store.mediaDir(recordId)
}

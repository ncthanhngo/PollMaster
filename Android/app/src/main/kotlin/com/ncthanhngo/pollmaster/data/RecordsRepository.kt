package com.ncthanhngo.pollmaster.data

import com.ncthanhngo.pollmaster.core.editor.EditorController
import com.ncthanhngo.pollmaster.core.editor.RecordCodes
import com.ncthanhngo.pollmaster.core.editor.RecordCreator
import com.ncthanhngo.pollmaster.core.editor.RecordFactory
import com.ncthanhngo.pollmaster.core.format.nextRecordSeq
import com.ncthanhngo.pollmaster.core.format.recordTitle
import com.ncthanhngo.pollmaster.core.format.nowIso
import com.ncthanhngo.pollmaster.core.model.Record
import com.ncthanhngo.pollmaster.core.model.Template
import com.ncthanhngo.pollmaster.core.model.allFields
import com.ncthanhngo.pollmaster.core.model.mediaItems
import com.ncthanhngo.pollmaster.core.model.recordCode
import com.ncthanhngo.pollmaster.core.project.Project
import com.ncthanhngo.pollmaster.core.storage.Backup
import com.ncthanhngo.pollmaster.core.sync.RecordAccess
import com.ncthanhngo.pollmaster.core.sync.isSyncDirty
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
    /** Signed-in ERP user id and the project of a record: who may edit it, and which leftovers of other accounts stay hidden. */
    private val me: () -> String? = { null },
    private val projectOf: (String?) -> Project? = { null },
    /** The signed-in account: new, duplicated and moved records are made out in its name straight away (schema 10.1). */
    private val creator: () -> RecordCreator? = { null },
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

    /** True while [recordId] is open in an editor (a pull must not overwrite it). */
    fun isOpen(recordId: String): Boolean = editors.containsKey(recordId)

    /**
     * Sync bookkeeping change to the current copy of a record: the open editor's, else the stored file. [change] returns null to leave it
     * alone. Returns the record as saved, null when it is gone.
     */
    fun updateForSync(recordId: String, change: (Record) -> Record?): Record? {
        editors[recordId]?.let { return it.applySync(change) }
        val current = store.load(recordId) ?: return null
        val next = change(current) ?: return current
        store.save(next)
        return next
    }

    /** Server-side follow-ups (set by [AppContainer]): a record was created, deleted (with its last state) or moved from [Record]'s project. */
    var onCreated: (Record) -> Unit = {}
    var onDeleted: (Record) -> Unit = {}
    var onMoved: (old: Record, new: Record) -> Unit = { _, _ -> }
    var onEditorOpened: (Record) -> Unit = {}

    /** Called once per 15 minutes while a record is open; set by [AppContainer]. */
    var onBackupTick: (String) -> Unit = {}

    private var lastPrint: List<Triple<String, Long, Long>>? = null
    private var lastTemplates: List<Template>? = null
    private var lastVisible: Set<String>? = null
    private var lastMe: String? = null

    /** Null when the signed-in user may edit [r]; else the banner text of its view-only state (ux-spec N). */
    fun readOnlyNote(r: Record): String? {
        val p = projectOf(r.projectId)
        if (RecordAccess.canEdit(r, me(), p)) return null
        return if (p?.canPost == false) "Bạn chỉ có quyền xem dự án này — phiếu chỉ xem" else "Phiếu của ${r.createdByName ?: "người khác"} — chỉ xem"
    }

    fun canEdit(r: Record): Boolean = readOnlyNote(r) == null

    fun canPost(projectId: String?): Boolean = projectOf(projectId)?.canPost ?: true

    /** Open editors follow permission changes that arrive with a sync. */
    private fun refreshAccess() = editors.values.forEach { it.setReadOnly(readOnlyNote(it.record.value)) }

    /** The stored file replaced the record (the server version was taken): open editors show it. */
    fun reloadFromStore(recordId: String) {
        val r = store.load(recordId) ?: return
        editors[recordId]?.reload(r)
    }

    /** Re-reads the list when record files, templates or visible projects changed since the last call; screens call this freely. */
    suspend fun refresh() {
        val templateList = templates.templates.value
        val visible = visibleProjects()
        val user = me()
        refreshAccess()
        val (print, stored) = withContext(Dispatchers.IO) {
            val print = store.fingerprint()
            if (print == lastPrint && templateList === lastTemplates && visible == lastVisible && user == lastMe) return@withContext print to null
            print to store.listAll()
        }
        if (stored == null) return
        val items = withContext(Dispatchers.IO) {
            stored.filterIsInstance<StoredRecord.Loaded>().map { it.record }.filter { it.projectId in visible }.filter { r ->
                // unsent changes of another account on this device stay hidden unless the user manages the project
                !RecordAccess.isHiddenLeftover(r.createdBy, user, r.isSyncDirty, projectOf(r.projectId)?.canManage ?: false)
            }.map { r ->
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
        lastPrint = print; lastTemplates = templateList; lastVisible = visible; lastMe = user
    }

    private fun thumbnailOf(template: Template, record: Record): File? {
        val photo = template.allFields().filter { it.type == "photo" }
            .firstNotNullOfOrNull { f -> record.values[f.id]?.mediaItems()?.firstOrNull() } ?: return null
        return File(store.dir(record.id), photo.file).takeIf { it.isFile }
    }

    suspend fun create(template: Template, projectId: String?): Record = withContext(Dispatchers.IO) {
        val existing = store.listAll().filterIsInstance<StoredRecord.Loaded>().map { it.record }
            .filter { it.projectId == projectId } // codes are numbered per project
        RecordFactory.create(template, settings.surveyorName.value, recordCode = RecordCodes.propose(template, existing), projectId = projectId, creator = creator())
            .copy(seq = nextRecordSeq(existing, projectId, template.id))
            .also { store.save(it); onCreated(it) }
    }

    /** Moves a record to another project; the record keeps its template, values and media, and takes the next number there. */
    suspend fun move(recordId: String, projectId: String) = withContext(Dispatchers.IO) {
        release(recordId)
        store.load(recordId)?.takeIf(::canEdit)?.let {
            val seq = nextRecordSeq(loadedRecords(), projectId, it.templateId)
            // on the server this is a delete in the old project and a new record in the target (schema 9.6, 10)
            val moved = it.copy(
                projectId = projectId, seq = seq, updatedAt = nowIso(), backupAt = null, erpUploadedAt = null,
                serverRev = null, createdBy = creator()?.id, createdByName = creator()?.name, syncConflict = false,
            ) // old backup is removed by the caller; a new one is due
            store.save(moved)
            onMoved(it, moved)
        }
    }

    suspend fun duplicate(item: RecordItem): Record? = withContext(Dispatchers.IO) {
        val t = item.template ?: return@withContext null
        flushEditor(item.record.id)
        val source = store.load(item.record.id)?.takeIf { canPost(it.projectId) } ?: return@withContext null
        RecordFactory.duplicate(t, source, creator = creator()).let { it.copy(seq = nextRecordSeq(loadedRecords(), it.projectId, it.templateId)) }.also { store.save(it) }
    }

    private fun loadedRecords(): List<Record> = store.listAll().filterIsInstance<StoredRecord.Loaded>().map { it.record }

    suspend fun delete(recordId: String) = withContext(Dispatchers.IO) {
        val old = store.load(recordId)
        if (old != null && !canEdit(old)) return@withContext      // someone else's record: only its owner or a manager deletes
        tickers.remove(recordId)?.cancel()
        editors.remove(recordId)?.flush()
        store.delete(recordId)
        old?.let(onDeleted)
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
        controller.setReadOnly(readOnlyNote(record))
        editors[recordId] = controller
        onEditorOpened(record)
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

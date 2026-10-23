package com.ncthanhngo.pollmaster.data

import com.ncthanhngo.pollmaster.core.editor.EditorController
import com.ncthanhngo.pollmaster.core.editor.RecordFactory
import com.ncthanhngo.pollmaster.core.format.recordTitle
import com.ncthanhngo.pollmaster.core.model.Record
import com.ncthanhngo.pollmaster.core.model.Template
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

data class RecordItem(val record: Record, val template: Template?, val title: String, val progress: Progress)

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
                )
            }
        }
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
        editors.remove(recordId)?.flush()
        store.delete(recordId)
    }

    /** Controller for editing [recordId]; cached until [release]. Null if record or template is missing. */
    fun editor(recordId: String): EditorController? {
        editors[recordId]?.let { return it }
        val record = store.load(recordId) ?: return null
        val template = templates.find(record.templateId, record.templateVersion) ?: return null
        val saver = DebouncedSaver(scope, 500, onError = { _saveError.value = it.message ?: "Lỗi ghi tệp" }) { store.save(it) }
        return EditorController(template, record, saver).also { editors[recordId] = it }
    }

    fun release(recordId: String) {
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

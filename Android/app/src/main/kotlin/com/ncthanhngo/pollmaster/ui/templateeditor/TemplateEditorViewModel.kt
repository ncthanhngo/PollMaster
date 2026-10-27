package com.ncthanhngo.pollmaster.ui.templateeditor

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ncthanhngo.pollmaster.AppContainer
import com.ncthanhngo.pollmaster.core.project.Project
import com.ncthanhngo.pollmaster.core.sync.PublishOutcome
import com.ncthanhngo.pollmaster.core.templateeditor.DocPath
import com.ncthanhngo.pollmaster.core.templateeditor.EditOp
import com.ncthanhngo.pollmaster.core.templateeditor.PathKey
import com.ncthanhngo.pollmaster.core.templateeditor.TemplateEdit
import com.ncthanhngo.pollmaster.core.templateeditor.docPath
import com.ncthanhngo.pollmaster.core.templateeditor.parseDocPath
import com.ncthanhngo.pollmaster.core.templateeditor.TemplateDoc
import com.ncthanhngo.pollmaster.core.templateeditor.TemplateDraft
import com.ncthanhngo.pollmaster.core.templateeditor.TemplateEditException
import com.ncthanhngo.pollmaster.core.templateeditor.TemplateIds
import com.ncthanhngo.pollmaster.core.templateeditor.TemplateIssue
import com.ncthanhngo.pollmaster.core.templateeditor.TemplateLint
import com.ncthanhngo.pollmaster.core.templateeditor.arr
import com.ncthanhngo.pollmaster.core.templateeditor.obj
import com.ncthanhngo.pollmaster.core.templateeditor.str
import com.ncthanhngo.pollmaster.data.TemplatePublisher
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** Where the editor is: it keeps its own page stack, so the draft state survives opening the preview and back. */
sealed interface EditorPage {
    data object Index : EditorPage
    data object Info : EditorPage

    /** A list of nodes: the header, a section's items, a table's columns or fixed rows, the signatures. */
    data class Items(val path: DocPath) : EditorPage
    data class Item(val listPath: DocPath, val index: Int) : EditorPage
    data class Json(val path: DocPath) : EditorPage
}

/** Holds the draft being edited: every change is an [EditOp] on the raw tree, saved to disk 500 ms after the last one. */
class TemplateEditorViewModel(private val container: AppContainer, draftId: String) : ViewModel() {
    private val drafts = container.templateDrafts

    var draft by mutableStateOf(drafts.drafts.value.firstOrNull { it.draftId == draftId })
        private set

    /** The installed version the draft started from (null for a new template). */
    val base: TemplateDoc? = draft?.let { d ->
        val id = d.baseId
        val version = d.baseVersion
        if (id == null || version == null) null else container.templateStore.raw(id, version)?.let { runCatching { TemplateDoc.of(it) }.getOrNull() }
    }

    /** Codes that exist in the base: locked in the form (an export merges columns by code, so a new code splits one). */
    val lockedIds: Set<String> = base?.let { b ->
        TemplateIds.usedIds(b.root) + b.root.obj()?.get("header").arr().orEmpty().mapNotNull { it.obj()?.get("id").str() } +
            columnIds(b.root)
    }.orEmpty()

    /** `fieldId|value` of every option in the base: those values are in old records, so they stay. */
    val lockedOptions: Set<String> = base?.let { b -> optionKeys(b.root) }.orEmpty()

    val pages = mutableStateListOf<EditorPage>(EditorPage.Index)
    val page: EditorPage get() = pages.last()

    val issues: List<TemplateIssue> by derivedStateOf {
        draft?.let { TemplateLint.check(it.doc, base, it.baseId == null) }.orEmpty()
    }
    val errors: List<TemplateIssue> get() = issues.filter { it.severity == TemplateIssue.Severity.ERROR }

    /** The sentence of the last refused edit; cleared by the next successful one. */
    var editError by mutableStateOf<String?>(null)
        private set

    val doc: TemplateDoc? get() = draft?.doc

    /** Id and version of a brand-new template can be typed until the first release. */
    val canEditTemplateId: Boolean get() = draft?.let { it.baseId == null && it.publishVersion == null } == true

    fun node(path: DocPath): JsonElement? = doc?.node(path)

    fun text(path: DocPath, key: String): String = node(path).obj()?.get(key).str().orEmpty()

    fun flag(path: DocPath, key: String): Boolean = (node(path).obj()?.get(key) as? JsonPrimitive)?.booleanOrNull == true

    /** A number stored under [key], printed without ".0". */
    fun number(path: DocPath, key: String): String {
        val p = node(path).obj()?.get(key) as? JsonPrimitive ?: return ""
        return p.content.removeSuffix(".0")
    }

    /** Applies [ops] in order; false (with [editError] set) when one is refused, leaving the draft as it was. */
    fun edit(vararg ops: EditOp): Boolean {
        val d = draft ?: return false
        return try {
            draft = d.withDoc(TemplateEdit.apply(d.doc, ops.toList()))
            editError = null
            schedule()
            true
        } catch (e: TemplateEditException) {
            editError = e.message
            false
        }
    }

    fun set(path: DocPath, key: String, text: String) = edit(EditOp.Set(path, key, JsonPrimitive(text)))

    fun setFlag(path: DocPath, key: String, on: Boolean) = edit(EditOp.Set(path, key, if (on) JsonPrimitive(true) else null))

    /** Blank removes the key; a half-typed number is ignored (the field keeps what was typed). */
    fun setNumber(path: DocPath, key: String, text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return edit(EditOp.Set(path, key, null))
        val n = t.toDoubleOrNull() ?: return false
        return edit(EditOp.Set(path, key, JsonPrimitive(if (n % 1.0 == 0.0 && kotlin.math.abs(n) < 1e15) n.toLong() else n)))
    }

    /** Things that would point at nothing if node [index] of the list at [path] were deleted (empty = nothing uses it). */
    fun danglingAfterDelete(path: DocPath, index: Int): List<TemplateIssue> {
        val d = draft ?: return emptyList()
        val after = runCatching { TemplateEdit.apply(d.doc, EditOp.Delete(path, index)) }.getOrNull() ?: return emptyList()
        val known = issues.filter { it.code == "DANGLING_REF" }.map { it.path to it.message }.toSet()
        return TemplateLint.check(after, base, d.baseId == null).filter { it.code == "DANGLING_REF" && (it.path to it.message) !in known }
    }

    fun open(p: EditorPage) { pages.add(p) }

    /** False when already at the index (the screen leaves). */
    fun back(): Boolean {
        if (pages.size <= 1) return false
        pages.removeAt(pages.size - 1)
        return true
    }

    fun setProject(project: Project) { draft = draft?.copy(projectId = project.id) }

    /** Pages for an issue [path] ("sections[1].items[3].options[0]"): the stack ends at the deepest form that shows it. */
    fun jump(path: String) {
        val d = doc ?: return
        pages.clear()
        pages.addAll(EditorPages.stackFor(d, path))
    }

    // ---- saving ----

    private val lock = Mutex()
    private var closed = false
    private var saveJob: Job? = null

    private fun schedule() {
        saveJob?.cancel()
        saveJob = container.scope.launch { delay(SAVE_DELAY_MS); persist() }
    }

    private suspend fun persist() = lock.withLock { if (!closed) draft?.let { drafts.save(it) } }

    fun flush() {
        saveJob?.cancel()
        container.scope.launch { persist() }
    }

    override fun onCleared() { flush() }

    /** "Bỏ nháp": deletes the draft; the editor must not write it again. */
    suspend fun discard() {
        val d = draft ?: return
        saveJob?.cancel()
        lock.withLock { closed = true; drafts.discard(d.draftId) }
    }

    /**
     * Saves the draft, then releases it. The lock stays held so a late autosave cannot overwrite the `publishVersion` the release
     * wrote; a finished release closes the editor for good (the draft is deleted).
     */
    suspend fun publish(target: Project, also: List<String>, force: Boolean): TemplatePublisher.Result {
        val d = draft?.copy(projectId = target.id) ?: return TemplatePublisher.Result(error = "Không tìm thấy bản nháp.")
        saveJob?.cancel()
        draft = d
        return lock.withLock {
            drafts.save(d)
            val result = container.templatePublisher.publish(d, target, also, force)
            if (result.outcome is PublishOutcome.Published) closed = true
            else drafts.drafts.value.firstOrNull { it.draftId == d.draftId }?.publishVersion?.let { v -> draft = d.copy(publishVersion = v) }
            result
        }
    }

    companion object {
        private const val SAVE_DELAY_MS = 500L

        fun factory(container: AppContainer, draftId: String): ViewModelProvider.Factory =
            viewModelFactory { initializer { TemplateEditorViewModel(container, draftId) } }

        private fun columnIds(root: JsonElement): Set<String> = root.obj()?.get("sections").arr().orEmpty().flatMap { s ->
            s.obj()?.get("items").arr().orEmpty().flatMap { i -> i.obj()?.get("columns").arr().orEmpty().mapNotNull { it.obj()?.get("id").str() } }
        }.toSet()

        private fun optionKeys(root: JsonElement): Set<String> {
            val fields = root.obj()?.get("header").arr().orEmpty() + root.obj()?.get("sections").arr().orEmpty().flatMap { s ->
                s.obj()?.get("items").arr().orEmpty().flatMap { i -> listOf(i) + i.obj()?.get("columns").arr().orEmpty() }
            }
            return fields.flatMap { f ->
                val id = f.obj()?.get("id").str().orEmpty()
                f.obj()?.get("options").arr().orEmpty().mapNotNull { o -> o.obj()?.get("value").str()?.let { "$id|$it" } }
            }.toSet()
        }
    }
}

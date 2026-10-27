package com.ncthanhngo.pollmaster.data

import com.ncthanhngo.pollmaster.core.format.nowIso
import com.ncthanhngo.pollmaster.core.model.Template
import com.ncthanhngo.pollmaster.core.project.Project
import com.ncthanhngo.pollmaster.core.project.TemplateRef
import com.ncthanhngo.pollmaster.core.storage.TemplateStore
import com.ncthanhngo.pollmaster.core.sync.TemplateSyncFailure
import com.ncthanhngo.pollmaster.core.templateeditor.TemplateDoc
import com.ncthanhngo.pollmaster.core.templateeditor.TemplateDraft
import com.ncthanhngo.pollmaster.core.templateeditor.TemplateDraftStore
import com.ncthanhngo.pollmaster.core.templateeditor.TemplatePublishRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Templates being edited on this device (schema 11): drafts in `template-drafts/`, who may start one and where it can be released.
 * [signedIn] false = nobody is signed in: the device's own list is free to edit.
 */
class TemplateDraftsRepository(
    root: File,
    private val templates: TemplatesRepository,
    private val projects: ProjectsRepository,
    private val signedIn: () -> Boolean,
) {
    private val store = TemplateDraftStore(root)
    private val templateStore = TemplateStore(root)

    private val _drafts = MutableStateFlow<List<TemplateDraft>>(emptyList())

    /** Newest edit first. */
    val drafts: StateFlow<List<TemplateDraft>> = _drafts.asStateFlow()

    suspend fun refresh() { _drafts.value = withContext(Dispatchers.IO) { store.list() } }

    fun draftFor(templateId: String): TemplateDraft? = _drafts.value.firstOrNull { it.templateId == templateId }

    /** Projects the user manages that use template [id] (null = any template): where an edit can be released. */
    fun managedProjects(id: String?): List<Project> = TemplatePublishRules.manageable(id, projects.projects.value)

    /** "Sửa nội dung": signed in = manages a project using it (offline is fine, release needs the network); signed out = free. */
    fun canDraft(t: Template): Boolean = !signedIn() || managedProjects(t.id).isNotEmpty()

    fun canCreate(): Boolean = !signedIn() || managedProjects(null).isNotEmpty()

    /** Managed projects a new template can go to. */
    fun publishTargets(): List<Project> = if (signedIn()) managedProjects(null) else projects.projects.value

    /** Other managed projects on the same template (ticked by default in the release sheet). */
    fun otherProjects(d: TemplateDraft, target: Project): List<Project> = TemplatePublishRules.otherProjects(d.templateId, target.id, projects.projects.value)

    /** Version older than [ref] on this device ("Quay lại bản trước"); null hides the button. */
    fun previousVersion(ref: TemplateRef): TemplateRef? =
        TemplatePublishRules.previousVersion(ref.version, templates.templates.value.filter { it.id == ref.id }.map { it.version })?.let { TemplateRef(ref.id, it) }

    private fun defaultProject(id: String?): Project? {
        val pool = if (signedIn()) managedProjects(id) else projects.projects.value.filter { p -> id == null || p.templates.any { it.id == id } } + projects.projects.value
        return pool.firstOrNull { it.id == projects.currentId.value } ?: pool.firstOrNull()
    }

    /** The device's draft for this template, or a new one from the installed version. Throws [TemplateSyncFailure] with a sentence to show. */
    suspend fun start(t: Template): TemplateDraft = withContext(Dispatchers.IO) {
        (draftFor(t.id) ?: store.existing(t.id))?.let { return@withContext it }
        val project = defaultProject(t.id) ?: throw TemplateSyncFailure("Cần có ít nhất một dự án để sửa mẫu.")
        val raw = templateStore.raw(t.id, t.version) ?: throw TemplateSyncFailure("Mẫu không có trên máy.")
        TemplateDraft.editing(t.id, t.version, TemplateDoc.of(raw).root, project.id, nowIso()).also(store::save)
    }.also { refresh() }

    /** "Tạo mẫu mới": empty, or a copy of [cloning]; [project] is where it will be released. */
    suspend fun create(project: Project, cloning: Template? = null): TemplateDraft = withContext(Dispatchers.IO) {
        val taken = templates.templates.value.map { it.id }.toSet()
        val source = cloning?.let { templateStore.raw(it.id, it.version) }
        val d = if (source != null) TemplateDraft.clone(TemplateDoc.of(source).root, project.id, taken, nowIso()) else TemplateDraft.blank(project.id, taken, nowIso())
        d.also(store::save)
    }.also { refresh() }

    /** Writes the draft (the editor calls it after every change, debounced). */
    suspend fun save(d: TemplateDraft) {
        withContext(Dispatchers.IO) { store.save(d.copy(updatedAt = nowIso())) }
        refresh()
    }

    suspend fun discard(draftId: String) {
        withContext(Dispatchers.IO) { store.delete(draftId) }
        refresh()
    }
}

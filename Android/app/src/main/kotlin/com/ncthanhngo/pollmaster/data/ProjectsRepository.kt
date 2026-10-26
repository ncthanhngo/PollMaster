package com.ncthanhngo.pollmaster.data

import com.ncthanhngo.pollmaster.core.format.nowIso
import com.ncthanhngo.pollmaster.core.project.Project
import com.ncthanhngo.pollmaster.core.project.ProjectDraft
import com.ncthanhngo.pollmaster.core.project.ProjectMigration
import com.ncthanhngo.pollmaster.core.project.ProjectRules
import com.ncthanhngo.pollmaster.core.project.ProjectStore
import com.ncthanhngo.pollmaster.core.project.TemplateRef
import com.ncthanhngo.pollmaster.core.storage.RecordStore
import com.ncthanhngo.pollmaster.core.storage.TemplateStore
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/** Projects (schema 9): list, current selection, templates and lifecycle. */
class ProjectsRepository(
    private val root: File,
    val store: ProjectStore,
    private val templateStore: TemplateStore,
    private val recordStore: RecordStore,
    private val sites: SitesRepository,
    private val settings: Settings,
) {
    private val _projects = MutableStateFlow<List<Project>>(emptyList())
    val projects: StateFlow<List<Project>> = _projects.asStateFlow()

    private val _avatars = MutableStateFlow<Map<String, ImageBitmap>>(emptyMap())

    /** Photo avatars by project id, read once per refresh (rows would otherwise hit the disk on every frame). */
    val avatars: StateFlow<Map<String, ImageBitmap>> = _avatars.asStateFlow()

    /** Selected project id, or null for "Tất cả dự án". */
    val currentId: StateFlow<String?> get() = settings.projectFilter

    fun select(id: String?) = settings.setProjectFilter(id)

    fun get(id: String?): Project? = id?.let { wanted -> _projects.value.firstOrNull { it.id == wanted } }

    /** Id of the project that owns records without a project of their own. */
    fun chungId(): String? = _projects.value.firstOrNull { it.id == Project.CHUNG_ID }?.id

    suspend fun refresh() {
        val (list, avatars) = withContext(Dispatchers.IO) {
            val list = store.list().also { p -> sites.reload(p.map { it.id }) }
            list to list.mapNotNull { p ->
                store.avatarFile(p.id)?.let { BitmapFactory.decodeFile(it.path) }?.let { p.id to it.asImageBitmap() }
            }.toMap()
        }
        _avatars.value = avatars
        _projects.value = list
        if (currentId.value != null && list.none { it.id == currentId.value }) select(null)
    }

    /** Runs the one-time data move (schema 9.2); safe on every start. */
    suspend fun migrate() {
        val result = withContext(Dispatchers.IO) { ProjectMigration.run(root, store, templateStore, recordStore, nowIso()) }
        result.renamedFrom?.let { old -> if (currentId.value == old) select(Project.CHUNG_ID) }
        refresh()
        if (!settings.hasProjectChoice) result.chung?.let { select(it.id) }
    }

    suspend fun create(draft: ProjectDraft, templates: List<TemplateRef> = emptyList()): Project {
        val p = withContext(Dispatchers.IO) { store.create(draft, nowIso(), templates) }
        refresh()
        return p
    }

    suspend fun update(id: String, draft: ProjectDraft) {
        withContext(Dispatchers.IO) { store.update(id, draft, nowIso()) }
        refresh()
    }

    suspend fun setArchived(id: String, archived: Boolean) {
        withContext(Dispatchers.IO) { store.setArchived(id, archived, nowIso()) }
        refresh()
    }

    /** Replaces the template list of a project. */
    suspend fun setTemplates(id: String, refs: List<TemplateRef>) {
        withContext(Dispatchers.IO) { store.setTemplates(id, refs, nowIso()) }
        refresh()
    }

    /** Adds [ref] to each of [projectIds] (no duplicates). */
    suspend fun addTemplate(ref: TemplateRef, projectIds: Collection<String>) {
        withContext(Dispatchers.IO) {
            projectIds.forEach { id -> store.get(id)?.let { store.setTemplates(id, it.templates + ref, nowIso()) } }
        }
        refresh()
    }

    /** Stores a 256 px JPEG as the avatar of [id], or removes it (back to initials) when [jpeg] is null. */
    suspend fun setAvatar(id: String, jpeg: ByteArray?) {
        withContext(Dispatchers.IO) { if (jpeg != null) store.setAvatar(id, jpeg) else store.removeAvatar(id) }
        refresh()
    }

    /** Deletes the project when no record belongs to it; false otherwise. */
    suspend fun delete(id: String): Boolean {
        val ok = withContext(Dispatchers.IO) {
            ProjectRules.canDelete(id, recordStore.list(), chungId()).also { if (it) store.delete(id) }
        }
        refresh()
        return ok
    }
}

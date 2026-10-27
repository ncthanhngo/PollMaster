package com.ncthanhngo.pollmaster.data

import com.ncthanhngo.pollmaster.core.format.nowIso
import com.ncthanhngo.pollmaster.core.project.Project
import com.ncthanhngo.pollmaster.core.project.ProjectStore
import com.ncthanhngo.pollmaster.core.project.TemplateRef
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** ERP projects on the device (schema 9): list, current selection, templates. */
class ProjectsRepository(
    val store: ProjectStore,
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

    private var lastPrint: List<Triple<String, Long, Long>>? = null

    /** Re-reads the projects when their files changed since the last call; screens call this freely. */
    suspend fun refresh() {
        val print = withContext(Dispatchers.IO) { store.fingerprint() }
        if (print != lastPrint) {
            val (list, avatars) = withContext(Dispatchers.IO) {
                // ERP projects this account sees; the others stay on the device, hidden with their records.
                val list = store.list().filter { !it.archived }.also { p -> sites.reload(p.map { it.id }) }
                list to list.mapNotNull { p ->
                    store.avatarFile(p.id)?.let { BitmapFactory.decodeFile(it.path) }?.let { p.id to it.asImageBitmap() }
                }.toMap()
            }
            _avatars.value = avatars
            _projects.value = list
            lastPrint = print
        }
        if (currentId.value != null && _projects.value.none { it.id == currentId.value }) select(null)
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
}

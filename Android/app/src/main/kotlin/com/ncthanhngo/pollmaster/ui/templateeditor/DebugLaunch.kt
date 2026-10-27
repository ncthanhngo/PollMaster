package com.ncthanhngo.pollmaster.ui.templateeditor

import android.content.Intent
import com.ncthanhngo.pollmaster.AppContainer
import com.ncthanhngo.pollmaster.BuildConfig
import com.ncthanhngo.pollmaster.core.format.nowIso
import com.ncthanhngo.pollmaster.core.project.Project
import com.ncthanhngo.pollmaster.core.project.TemplateRef
import com.ncthanhngo.pollmaster.core.theme.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Debug builds only (`BuildConfig.DEBUG`): opens the template editor straight from adb, without signing in, to look at layouts.
 * `adb shell am start -n com.ncthanhngo.pollmaster/.MainActivity --es debug_editor <templateId|blank> [--es debug_theme dark|light] [--es debug_path "sections[0].items[1]"]`.
 * Release builds never read the extras.
 */
object DebugLaunch {
    class Request(val template: String, val theme: String?, val path: String?)

    val request = MutableStateFlow<Request?>(null)

    /** Jump target for the editor that opens next; read once. */
    @Volatile private var pendingPath: String? = null

    fun read(intent: Intent?) {
        if (!BuildConfig.DEBUG) return
        val template = intent?.getStringExtra("debug_editor") ?: return
        request.value = Request(template, intent.getStringExtra("debug_theme"), intent.getStringExtra("debug_path"))
    }

    fun takePath(): String? = pendingPath.also { pendingPath = null }

    /** Draft id of a new blank template or of an edit of an installed one, in a throw-away local project when the device has none. */
    suspend fun open(container: AppContainer, r: Request): String? {
        r.theme?.let { container.settings.setThemeMode(if (it == "dark") ThemeMode.DARK else ThemeMode.LIGHT) }
        pendingPath = r.path
        container.templates.installBundled()
        container.templates.refresh()
        val drafts = container.templateDrafts
        if (container.projects.projects.value.isEmpty()) {
            container.projectStore.save(
                Project(
                    id = "debug-project", code = "DBG", name = "Dự án thử", canManage = true, createdAt = nowIso(), updatedAt = nowIso(),
                    templates = container.templates.templates.value.map { TemplateRef(it.id, it.version) },
                ),
            )
            container.projects.refresh()
        }
        val project = container.projects.projects.value.firstOrNull() ?: return null
        if (r.template == "blank") return drafts.create(project).draftId
        val template = container.templates.templates.value.firstOrNull { it.id == r.template } ?: return null
        return drafts.start(template).draftId
    }
}

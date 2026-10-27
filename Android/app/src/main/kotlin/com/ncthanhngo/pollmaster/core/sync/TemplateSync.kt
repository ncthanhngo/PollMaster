package com.ncthanhngo.pollmaster.core.sync

import com.ncthanhngo.pollmaster.core.model.PollJson
import com.ncthanhngo.pollmaster.core.project.Project
import com.ncthanhngo.pollmaster.core.project.ProjectStore
import com.ncthanhngo.pollmaster.core.project.TemplateRef
import com.ncthanhngo.pollmaster.core.project.startsWithLibrary
import com.ncthanhngo.pollmaster.core.storage.InstallResult
import com.ncthanhngo.pollmaster.core.storage.TemplateStore
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** A template the server assigned to a project (`assigned` of `GET poll-templates`). */
@Serializable
data class ServerTemplateRef(@SerialName("template_id") val templateId: String, val version: String, val position: Int = 0) {
    val ref: TemplateRef get() = TemplateRef(templateId, version)
}

/** Library entry with its full template JSON. */
@Serializable
data class ServerTemplate(@SerialName("template_id") val templateId: String, val version: String, val content: JsonElement)

/** `data` of `GET /projects/:id/poll-templates` (template-schema 10.6). */
@Serializable
data class ProjectTemplatesInfo(
    @SerialName("can_manage") val canManage: Boolean = false,
    val assigned: List<ServerTemplateRef> = emptyList(),
    @SerialName("removed_ids") val removedIds: List<String> = emptyList(),
    val templates: List<ServerTemplate> = emptyList(),
)

/** A refusal or problem worth a sentence for the user (permission, bad template, server message). Network errors are thrown as they are. */
class TemplateSyncFailure(message: String) : Exception(message)

/** `TEMPLATE_VERSION_EXISTS` (409): the id@version is in the organisation library with other content. */
class TemplateVersionExists : Exception("Máy chủ đã có mẫu này (cùng mã và phiên bản) với nội dung khác. Đổi số phiên bản của mẫu rồi thử lại.")

/** The template calls of the ERP backend (`project-poll-templates.md`). The app implements it over HTTP; tests use a fake. */
interface TemplateRemote {
    /** Null when the server has no such API or the project is not visible (404): the device keeps its own list. */
    suspend fun list(projectId: String): ProjectTemplatesInfo?

    /** POST: adds [content] (a template JSON object) to the library and assigns it. */
    suspend fun add(projectId: String, content: JsonElement)

    suspend fun patch(projectId: String, templateId: String, version: String?, title: String?, shortName: String?, color: String?)

    /** DELETE: false when the server never had the template in this project (404). */
    suspend fun remove(projectId: String, templateId: String): Boolean
}

/** What the server must be told after reading a project's templates (phase 07). Pure: both platforms run the shared vectors. */
object TemplateSyncPlanner {
    /** [post] = templates to POST; [templates] = what `project.templates` becomes once those POSTs succeed. */
    data class Result(val post: List<TemplateRef>, val templates: List<TemplateRef>)

    /**
     * [local]: the project's list on the device. [assigned]/[removedIds]: the server's. [library]: newest version of every template on the
     * device. [bundled]: newest bundled versions. A server with no row for the project (both lists empty) is "not initialised": the
     * manager's device uploads its list (a Phoenix project without templates gets the whole library); others keep their own list.
     * A manager of a Phoenix project also uploads bundled templates the server neither assigns nor remembers as removed.
     */
    fun plan(
        local: List<TemplateRef>, assigned: List<TemplateRef>, removedIds: List<String>, canManage: Boolean, isPhoenix: Boolean,
        library: List<TemplateRef>, bundled: List<TemplateRef>,
    ): Result {
        val initial = assigned.isEmpty() && removedIds.isEmpty()
        if (!canManage) return Result(emptyList(), if (initial) local else assigned)
        var post = if (initial) (if (isPhoenix && local.isEmpty()) library else local) else emptyList()
        if (isPhoenix) {
            val known = assigned.map { it.id }.toSet() + removedIds + post.map { it.id }
            post = post + bundled.filter { it.id !in known }
        }
        return Result(post, if (initial) post else assigned + post)
    }
}

/** Reads and writes a project's templates through the server; the device copy follows only what the server confirmed (no offline queue). */
class TemplateSync(
    private val projects: ProjectStore,
    private val templates: TemplateStore,
    private val remote: TemplateRemote,
) {
    class Outcome(
        /** The device's templates or project list changed (reload the screens). */
        var changed: Boolean = false,
        /** The server has no template API for this project: nothing was done. */
        var unavailable: Boolean = false,
        var posted: Int = 0,
        /** First POST the server refused (the rest of the run is not affected). */
        var failure: String? = null,
    )

    /** Per-project step before the records (phase 07): read, install, set `project.templates`, upload what the plan says, read again. */
    suspend fun sync(projectId: String, library: List<TemplateRef>, bundled: List<TemplateRef>): Outcome {
        val out = Outcome()
        val project = projects.get(projectId)?.takeIf { !it.archived } ?: return out
        val info = remote.list(projectId) ?: return out.also { it.unavailable = true }
        val initial = info.assigned.isEmpty() && info.removedIds.isEmpty()
        out.changed = apply(info, projectId, keepList = initial)
        val plan = TemplateSyncPlanner.plan(
            project.templates, info.assigned.map { it.ref }, info.removedIds, info.canManage, project.startsWithLibrary, library, bundled,
        )
        for (ref in plan.post) {
            val content = content(ref) ?: continue
            try {
                remote.add(projectId, content)
                out.posted++
            } catch (e: TemplateSyncFailure) {
                out.failure = out.failure ?: e.message
            } catch (_: TemplateVersionExists) {
                // the library holds other content for that id@version: not for this run to fix
            }
        }
        if (out.posted > 0) remote.list(projectId)?.let { if (apply(it, projectId, keepList = false)) out.changed = true }
        return out
    }

    /** Re-reads the project's templates and applies them (after an action the server confirmed). */
    suspend fun refresh(projectId: String): Boolean {
        val info = remote.list(projectId) ?: throw TemplateSyncFailure("Máy chủ chưa hỗ trợ mẫu theo dự án.")
        return apply(info, projectId, keepList = info.assigned.isEmpty() && info.removedIds.isEmpty())
    }

    /** Adds templates (from the device library) to the project. */
    suspend fun assign(refs: List<TemplateRef>, projectId: String) {
        var failure: Exception? = null
        for (ref in refs) {
            try { remote.add(projectId, requireContent(ref)) } catch (e: Exception) { if (e is CancellationException) throw e; failure = e; break }
        }
        runCatching { refresh(projectId) }          // whatever the server took is shown even when a later one failed
        failure?.let { throw it }
    }

    /** Removes a template from the project. A template the server never had is uploaded first so the removal is remembered. */
    suspend fun unassign(ref: TemplateRef, projectId: String) {
        if (!remote.remove(projectId, ref.id)) {
            remote.add(projectId, requireContent(ref))
            remote.remove(projectId, ref.id)
        }
        refresh(projectId)
    }

    /** Switches the project to another version of a template (a newer one for new records). The version may exist only on this device. */
    suspend fun useVersion(ref: TemplateRef, projectId: String) {
        try {
            remote.add(projectId, requireContent(ref))
        } catch (_: TemplateVersionExists) {
            remote.patch(projectId, ref.id, ref.version, null, null, null)
        }
        refresh(projectId)
    }

    /** Name, short name, colour of the template version assigned in [projectId] (applies to the whole organisation library). */
    suspend fun editMeta(ref: TemplateRef, projectId: String, title: String, shortName: String, color: String) {
        remote.patch(projectId, ref.id, null, title, shortName, color)
        refresh(projectId)
    }

    // ---- internals ----

    private fun content(ref: TemplateRef): JsonElement? = templates.raw(ref.id, ref.version)?.let { runCatching { PollJson.parseToJsonElement(it) }.getOrNull() }

    private fun requireContent(ref: TemplateRef): JsonElement =
        content(ref) ?: throw TemplateSyncFailure("Mẫu ${ref.id} v${ref.version} không có trong thư viện trên máy.")

    /** Installs the server's templates (server wins on id@version), then the project's list and removed ids. Returns whether anything changed. */
    private fun apply(info: ProjectTemplatesInfo, projectId: String, keepList: Boolean): Boolean {
        var changed = false
        for (t in info.templates) {
            val obj = (t.content as? JsonObject) ?: continue
            if (obj["id"]?.jsonPrimitive?.contentOrNull != t.templateId || obj["version"]?.jsonPrimitive?.contentOrNull != t.version) continue
            val mine = templates.raw(t.templateId, t.version)?.let { runCatching { PollJson.parseToJsonElement(it) }.getOrNull() }
            if (mine == t.content) continue
            if (templates.install(t.content.toString(), overwrite = true) is InstallResult.Installed) changed = true     // not a usable template: skipped
        }
        val p: Project = projects.get(projectId) ?: return changed
        val list = if (keepList) p.templates else info.assigned.map { it.ref }
        if (p.templates != list || p.removedTemplateIds != info.removedIds) {
            projects.save(p.copy(templates = list, removedTemplateIds = info.removedIds))
            changed = true
        }
        return changed
    }
}

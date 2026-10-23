package com.ncthanhngo.pollmaster.core.project

import com.ncthanhngo.pollmaster.core.model.PollJson
import com.ncthanhngo.pollmaster.core.sites.SitesStore
import com.ncthanhngo.pollmaster.core.storage.writeAtomically
import java.io.File
import java.util.UUID

/** Raised for input the user can fix (blank name, bad or taken code); the message is shown as is. */
class ProjectException(message: String) : Exception(message)

/** Editable parts of a project. */
data class ProjectDraft(
    val name: String,
    val code: String,
    val client: String? = null,
    val description: String? = null,
    val color: String? = null,
)

/** `<root>/projects/<projectId>/project.json` + `sites.json`. */
class ProjectStore(root: File) {
    private val dir = File(root, "projects")

    fun dir(id: String): File = File(dir, id)

    /** Planned points of one project (schema 9.1). */
    fun sites(id: String): SitesStore = SitesStore(dir(id).also { it.mkdirs() })

    /** Active projects first, then archived, each by name. */
    fun list(): List<Project> = dir.listFiles { f -> f.isDirectory }.orEmpty().mapNotNull { get(it.name) }
        .sortedWith(compareBy<Project> { it.archived }.thenBy { it.name.lowercase() })

    fun get(id: String): Project? {
        val file = File(dir(id), FILE)
        if (!file.isFile) return null
        return runCatching { PollJson.decodeFromString(Project.serializer(), file.readText()) }.getOrNull()
    }

    fun byCode(code: String): Project? = list().firstOrNull { it.code.equals(code, ignoreCase = true) }

    fun save(project: Project) {
        writeAtomically(File(dir(project.id), FILE), PollJson.encodeToString(Project.serializer(), project).toByteArray(Charsets.UTF_8))
    }

    fun create(draft: ProjectDraft, now: String, templates: List<TemplateRef> = emptyList(), id: String = UUID.randomUUID().toString()): Project {
        validate(draft, ownId = null)
        return Project(
            id = id, code = draft.code.trim().uppercase(), name = draft.name.trim(), client = draft.client.clean(),
            description = draft.description.clean(), color = draft.color, templates = templates, createdAt = now, updatedAt = now,
        ).also(::save)
    }

    fun update(id: String, draft: ProjectDraft, now: String): Project {
        val old = get(id) ?: throw ProjectException("Không tìm thấy dự án")
        validate(draft, ownId = id)
        return old.copy(
            code = draft.code.trim().uppercase(), name = draft.name.trim(), client = draft.client.clean(),
            description = draft.description.clean(), color = draft.color, updatedAt = now,
        ).also(::save)
    }

    /** Re-keys a project (folder and `id`), e.g. a "Chung" created with a random id. False when [newId] is taken or [oldId] is missing. */
    fun changeId(oldId: String, newId: String): Boolean {
        val project = get(oldId) ?: return false
        if (dir(newId).exists() || !dir(oldId).renameTo(dir(newId))) return false
        save(project.copy(id = newId))
        return true
    }

    fun setArchived(id: String, archived: Boolean, now: String): Project? =
        get(id)?.copy(archived = archived, updatedAt = now)?.also(::save)

    fun setTemplates(id: String, templates: List<TemplateRef>, now: String): Project? =
        get(id)?.copy(templates = templates.distinct(), updatedAt = now)?.also(::save)

    /** Removes the project folder (its sites included); callers make sure no record still belongs to it. */
    fun delete(id: String): Boolean = dir(id).deleteRecursively()

    /** Codes in use (upper case), optionally without the project [except]. */
    fun codes(except: String? = null): Set<String> = list().filter { it.id != except }.map { it.code.uppercase() }.toSet()

    private fun validate(draft: ProjectDraft, ownId: String?) {
        if (draft.name.isBlank()) throw ProjectException("Cần nhập tên dự án")
        val code = draft.code.trim().uppercase()
        if (!ProjectCodes.isValid(code)) throw ProjectException("Mã dự án chỉ gồm A-Z, 0-9 và dấu -, tối đa ${ProjectCodes.MAX_LENGTH} ký tự")
        if (code in codes(except = ownId)) throw ProjectException("Mã dự án \"$code\" đã được dùng")
    }

    private fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

    companion object {
        const val FILE = "project.json"
    }
}

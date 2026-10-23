package com.ncthanhngo.pollmaster.core.storage

import com.ncthanhngo.pollmaster.core.model.Template
import java.io.File

sealed interface InstallResult {
    data class Installed(val template: Template) : InstallResult
    data class Exists(val template: Template) : InstallResult
    data class Invalid(val message: String) : InstallResult
}

/** `<root>/templates/<id>@<version>.json`. */
class TemplateStore(root: File) {
    private val dir = File(root, "templates")

    fun list(): List<Template> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty()
            .mapNotNull { runCatching { TemplateParser.parse(it.readText()) }.getOrNull() }
            .sortedWith(compareBy({ it.title }, { it.version }))

    fun get(id: String, version: String): Template? =
        file(id, version).takeIf { it.isFile }?.let { runCatching { TemplateParser.parse(it.readText()) }.getOrNull() }

    /** Validates and stores [json]; an existing id+version is only replaced when [overwrite] is set. */
    fun install(json: String, overwrite: Boolean): InstallResult {
        val template = try {
            TemplateParser.parse(json)
        } catch (e: TemplateException) {
            return InstallResult.Invalid(e.message.orEmpty())
        }
        val target = file(template.id, template.version)
        if (target.exists() && !overwrite) return InstallResult.Exists(template)
        writeAtomically(target, json.toByteArray(Charsets.UTF_8))
        return InstallResult.Installed(template)
    }

    fun delete(id: String, version: String): Boolean = file(id, version).delete()

    private fun file(id: String, version: String) = File(dir, "${safe(id)}@${safe(version)}.json")

    private fun safe(s: String) = s.replace(Regex("[^A-Za-z0-9._-]"), "_")
}

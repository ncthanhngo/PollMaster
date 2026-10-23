package com.ncthanhngo.pollmaster.core.project

import com.ncthanhngo.pollmaster.core.storage.InstallResult
import com.ncthanhngo.pollmaster.core.storage.TemplateStore

/** What importing a bundle would do, shown in the preview before the user confirms. */
data class ImportPlan(
    /** Existing project with the same id that would be updated (records untouched), or null for a new project. */
    val updates: Project?,
    /** Code the project will have: the bundle's code, or with `-2` when another project uses it. */
    val code: String,
    /** Templates (id@version) already installed with different content: they are replaced only when the user agrees. */
    val conflicts: List<TemplateRef>,
    /** Templates the project lists that are neither in the bundle nor installed: dropped from the project. */
    val dropped: List<TemplateRef> = emptyList(),
)

data class ImportOutcome(val project: Project, val installed: List<TemplateRef>, val kept: List<TemplateRef>, val dropped: List<TemplateRef> = emptyList())

/** Applies a [BundleContents] to the stores (schema 9.3). */
object ProjectImporter {
    fun plan(contents: BundleContents, projects: ProjectStore, templates: TemplateStore): ImportPlan {
        val existing = projects.get(contents.project.id)
        val code = existing?.code ?: codeFor(contents.project.code, null, projects)
        val conflicts = contents.templates.filter { bt ->
            val installed = templates.get(bt.template.id, bt.template.version)
            installed != null && installed != bt.template
        }.map { TemplateRef(it.template.id, it.template.version) }
        return ImportPlan(existing, code, conflicts, missing(contents, templates))
    }

    private fun missing(contents: BundleContents, templates: TemplateStore): List<TemplateRef> {
        val inBundle = contents.templates.map { TemplateRef(it.template.id, it.template.version) }.toSet()
        return contents.project.templates.distinct().filter { it !in inBundle && templates.get(it.id, it.version) == null }
    }

    /** Same id: update name/templates/sites (keeps the local code and archived state). Otherwise a new project. */
    fun apply(contents: BundleContents, projects: ProjectStore, templates: TemplateStore, overwriteTemplates: Boolean, now: String): ImportOutcome {
        val installed = mutableListOf<TemplateRef>()
        val kept = mutableListOf<TemplateRef>()
        for (bt in contents.templates) {
            val ref = TemplateRef(bt.template.id, bt.template.version)
            when (val r = templates.install(bt.json, overwrite = overwriteTemplates)) {
                is InstallResult.Installed -> installed += ref
                is InstallResult.Exists -> kept += ref
                is InstallResult.Invalid -> throw ProjectException("Mẫu ${ref.id}: ${r.message}")
            }
        }
        val src = contents.project
        val existing = projects.get(src.id)
        val dropped = missing(contents, templates)
        val refs = src.templates.distinct().filter { it !in dropped }
        val project = existing?.copy(
            name = src.name, client = src.client, description = src.description, color = src.color, templates = refs, updatedAt = now,
        ) ?: Project(
            id = src.id, code = codeFor(src.code, null, projects), name = src.name, client = src.client, description = src.description,
            color = src.color, templates = refs, createdAt = now, updatedAt = now,
        )
        projects.save(project)
        contents.sites?.let { projects.sites(project.id).save(it) }
        return ImportOutcome(project, installed, kept, dropped)
    }

    private fun codeFor(wanted: String, existing: Project?, projects: ProjectStore): String =
        ProjectCodes.unique(wanted.uppercase(), projects.codes(except = existing?.id))
}

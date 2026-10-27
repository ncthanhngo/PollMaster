package com.ncthanhngo.pollmaster.core.project

import com.ncthanhngo.pollmaster.core.storage.InstallResult
import com.ncthanhngo.pollmaster.core.storage.TemplateStore

/** What importing a bundle would do, shown in the preview before the user confirms. */
data class ImportPlan(
    /** The bundle's own project when it is one of the listed ERP projects: proposed as the target. */
    val proposed: Project?,
    /** Templates (id@version) already installed with different content: they are replaced only when the user agrees. */
    val conflicts: List<TemplateRef>,
    /** Templates the project lists that are neither in the bundle nor installed: dropped from the project. */
    val dropped: List<TemplateRef> = emptyList(),
)

data class ImportOutcome(val project: Project, val installed: List<TemplateRef>, val kept: List<TemplateRef>, val dropped: List<TemplateRef> = emptyList())

/**
 * Applies a [BundleContents] (schema 9.3) to an ERP project on the device: templates installed and added to the project (a bundled
 * version replaces the one the project used), sites replaced when the bundle has them. Projects are never created here.
 */
object ProjectImporter {
    fun plan(contents: BundleContents, projects: ProjectStore, templates: TemplateStore): ImportPlan {
        val proposed = projects.get(contents.project.id)?.takeIf { !it.archived }
        val conflicts = contents.templates.filter { bt ->
            val installed = templates.get(bt.template.id, bt.template.version)
            installed != null && installed != bt.template
        }.map { TemplateRef(it.template.id, it.template.version) }
        return ImportPlan(proposed, conflicts, missing(contents, templates))
    }

    private fun missing(contents: BundleContents, templates: TemplateStore): List<TemplateRef> {
        val inBundle = contents.templates.map { TemplateRef(it.template.id, it.template.version) }.toSet()
        return contents.project.templates.distinct().filter { it !in inBundle && templates.get(it.id, it.version) == null }
    }

    fun apply(
        contents: BundleContents, targetId: String, projects: ProjectStore, templates: TemplateStore, overwriteTemplates: Boolean, now: String,
    ): ImportOutcome {
        val target = projects.get(targetId) ?: throw ProjectException("Không tìm thấy dự án")
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
        val dropped = missing(contents, templates)
        val refs = contents.project.templates.distinct().filter { it !in dropped }
        val merged = target.templates.toMutableList()
        for (ref in refs) {
            val i = merged.indexOfFirst { it.id == ref.id }
            if (i >= 0) merged[i] = ref else merged += ref
        }
        val project = target.copy(templates = merged, updatedAt = now)
        projects.save(project)
        contents.sites?.let { projects.sites(project.id).save(it) }
        return ImportOutcome(project, installed, kept, dropped)
    }
}

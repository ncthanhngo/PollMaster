package com.ncthanhngo.pollmaster.core.project

import com.ncthanhngo.pollmaster.core.sites.SitesStore
import com.ncthanhngo.pollmaster.core.storage.RecordStore
import com.ncthanhngo.pollmaster.core.storage.TemplateStore
import com.ncthanhngo.pollmaster.core.storage.newestVersions
import java.io.File

/** Result of [ProjectMigration.run]: the "Chung" project (null when nothing needed it), records it adopted, and its old id if it was re-keyed. */
data class MigrationResult(val chung: Project?, val adopted: Int, val movedSites: Boolean, val renamedFrom: String? = null)

/** First run of a build with projects (schema 9.2). Safe to run on every start: it only acts on what is still unassigned. */
object ProjectMigration {
    fun run(root: File, projects: ProjectStore, templates: TemplateStore, records: RecordStore, now: String): MigrationResult {
        var renamedFrom: String? = null
        // A "Chung" made with a random id (earlier build) is re-keyed to the fixed id so packages match across devices.
        projects.byCode(Project.CHUNG_CODE)?.takeIf { it.id != Project.CHUNG_ID }?.let { old ->
            if (projects.changeId(old.id, Project.CHUNG_ID)) {
                renamedFrom = old.id
                records.list().filter { it.projectId == old.id }.forEach { records.save(it.copy(projectId = Project.CHUNG_ID)) }
            }
        }
        val known = projects.list().map { it.id }.toSet()
        val orphans = records.list().filter { it.projectId == null || it.projectId !in known }
        val globalSites = SitesStore(root)
        val hasGlobalSites = File(root, "sites.json").isFile
        var chung = projects.get(Project.CHUNG_ID)
        if (chung == null && (orphans.isNotEmpty() || hasGlobalSites || known.isEmpty())) {
            val refs = newestVersions(templates.list()).map { TemplateRef(it.id, it.version) }
            chung = projects.create(ProjectDraft(Project.CHUNG_NAME, Project.CHUNG_CODE), now, refs, id = Project.CHUNG_ID)
        }
        if (chung == null) return MigrationResult(null, 0, false, renamedFrom)
        var movedSites = false
        if (hasGlobalSites) {
            val target = projects.sites(chung.id)
            if (target.load().isEmpty()) target.save(globalSites.load())
            globalSites.clear()
            movedSites = true
        }
        orphans.forEach { records.save(it.copy(projectId = chung.id)) }
        return MigrationResult(chung, orphans.size, movedSites, renamedFrom)
    }
}

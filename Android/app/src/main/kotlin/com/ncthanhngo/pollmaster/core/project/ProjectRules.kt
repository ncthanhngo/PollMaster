package com.ncthanhngo.pollmaster.core.project

import com.ncthanhngo.pollmaster.core.model.Record

/** Business rules about records and projects that need no storage. */
object ProjectRules {
    /** Projects a record can move to: other, not archived projects that list the record's template (any version). */
    fun moveTargets(record: Record, projects: List<Project>, currentProjectId: String? = record.projectId): List<Project> =
        projects.filter { !it.archived && it.id != currentProjectId && it.templates.any { t -> t.id == record.templateId } }

    /** `{newer version}` of [ref]'s template when the library has a newer one, else null. */
    fun newerVersion(ref: TemplateRef, library: List<TemplateRef>, compare: (String, String) -> Int): String? =
        library.filter { it.id == ref.id && compare(it.version, ref.version) > 0 }.maxWithOrNull { a, b -> compare(a.version, b.version) }?.version
}

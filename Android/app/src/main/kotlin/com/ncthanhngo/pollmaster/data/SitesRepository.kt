package com.ncthanhngo.pollmaster.data

import com.ncthanhngo.pollmaster.core.project.ProjectStore
import com.ncthanhngo.pollmaster.core.sites.Site
import com.ncthanhngo.pollmaster.core.sites.SitesCsv
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Planned points of every project (schema 7.3 / 9.1), kept in memory and in each project's `sites.json`. */
class SitesRepository(private val projects: ProjectStore) {
    private val _all = MutableStateFlow<Map<String, List<Site>>>(emptyMap())
    /** Project id -> its sites. */
    val all: StateFlow<Map<String, List<Site>>> = _all.asStateFlow()

    /** Re-reads the sites of [projectIds] from disk (and forgets other projects). */
    fun reload(projectIds: Collection<String>) {
        _all.value = projectIds.associateWith { projects.sites(it).load() }
    }

    /** Replaces the project's list with the CSV content; throws [com.ncthanhngo.pollmaster.core.sites.SitesCsvException] on a bad header. */
    fun importCsv(projectId: String, text: String): Int {
        val parsed = SitesCsv.parse(text)
        projects.sites(projectId).save(parsed)
        _all.value = _all.value + (projectId to parsed)
        return parsed.size
    }

    fun clear(projectId: String) {
        projects.sites(projectId).clear()
        _all.value = _all.value + (projectId to emptyList())
    }
}

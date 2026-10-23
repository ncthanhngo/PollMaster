package com.ncthanhngo.pollmaster.data

import com.ncthanhngo.pollmaster.core.sites.Site
import com.ncthanhngo.pollmaster.core.sites.SitesCsv
import com.ncthanhngo.pollmaster.core.sites.SitesStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Imported planned points (schema 7.3), kept in memory and in `sites.json`. */
class SitesRepository(private val store: SitesStore) {
    private val _sites = MutableStateFlow(store.load())
    val sites: StateFlow<List<Site>> = _sites.asStateFlow()

    /** Replaces the list with the CSV content; throws [com.ncthanhngo.pollmaster.core.sites.SitesCsvException] on a bad header. */
    fun importCsv(text: String): Int {
        val parsed = SitesCsv.parse(text)
        store.save(parsed)
        _sites.value = parsed
        return parsed.size
    }

    fun clear() {
        store.clear()
        _sites.value = emptyList()
    }
}

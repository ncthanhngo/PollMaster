package com.ncthanhngo.pollmaster.data

import android.content.Context
import com.ncthanhngo.pollmaster.core.model.Template
import com.ncthanhngo.pollmaster.core.project.ProjectStore
import com.ncthanhngo.pollmaster.core.storage.InstallResult
import com.ncthanhngo.pollmaster.core.storage.RecordStore
import com.ncthanhngo.pollmaster.core.storage.TemplateParser
import com.ncthanhngo.pollmaster.core.storage.TemplateStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

sealed interface DeleteResult {
    data object Deleted : DeleteResult
    data class InUse(val count: Int) : DeleteResult
    /** Listed by one or more projects (their codes). */
    data class UsedByProjects(val codes: List<String>) : DeleteResult
}

class TemplatesRepository(
    private val context: Context,
    private val store: TemplateStore,
    private val records: RecordStore,
    private val settings: Settings,
    private val projects: ProjectStore,
) {
    private val _templates = MutableStateFlow<List<Template>>(emptyList())
    val templates: StateFlow<List<Template>> = _templates.asStateFlow()

    suspend fun refresh() {
        reload(withContext(Dispatchers.IO) { store.list() })
    }

    /** Publishes [fresh]; an unchanged list keeps its identity, and editors holding a replaced template are closed. */
    private fun reload(fresh: List<Template>) {
        val old = _templates.value
        if (fresh == old) return
        _templates.value = fresh
        fresh.filter { f -> old.any { it.id == f.id && it.version == f.version && it != f } }.forEach { onReplaced(it.id, it.version) }
    }

    /** Called after a template file was replaced, so editors holding the old copy can be closed. */
    var onReplaced: (id: String, version: String) -> Unit = { _, _ -> }

    /** From the loaded list (no disk read per call); falls back to the file before the first load. */
    fun find(id: String, version: String): Template? =
        _templates.value.firstOrNull { it.id == id && it.version == version } ?: store.get(id, version)

    /** Installs every bundled template version once (keyed by id@version, so a new version is added next to the old one). */
    suspend fun installBundled() = withContext(Dispatchers.IO) {
        val done = settings.installedBundled.toMutableSet()
        val names = context.assets.list("").orEmpty().filter { it.endsWith(".json") }
        for (name in names) {
            val text = context.assets.open(name).use { it.readBytes().decodeToString() }
            val template = runCatching { TemplateParser.parse(text) }.getOrNull() ?: continue
            val key = "${template.id}@${template.version}"
            if (key in done) continue
            if (store.install(text, overwrite = false) !is InstallResult.Invalid) done += key
        }
        settings.installedBundled = done
        reload(store.list())
    }

    suspend fun install(json: String, overwrite: Boolean): InstallResult = withContext(Dispatchers.IO) {
        store.install(json, overwrite).also { reload(store.list()) }
    }

    suspend fun readUri(uri: android.net.Uri): String = withContext(Dispatchers.IO) {
        val stream = context.contentResolver.openInputStream(uri) ?: throw IOException("Không đọc được tệp")
        stream.use { limited(it.readBytes()) }
    }

    /** Downloads a template JSON (http/https only, max 5 MB). */
    suspend fun download(url: String): String = withContext(Dispatchers.IO) {
        val parsed = URL(url.trim())
        if (parsed.protocol != "https" && parsed.protocol != "http") throw IOException("Chỉ hỗ trợ http/https")
        val conn = (parsed.openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
        }
        try {
            if (conn.responseCode !in 200..299) throw IOException("Máy chủ trả về ${conn.responseCode}")
            limited(conn.inputStream.use { it.readBytes() })
        } finally {
            conn.disconnect()
        }
    }

    private fun limited(bytes: ByteArray): String {
        if (bytes.size > MAX_BYTES) throw IOException("Tệp quá lớn (> 5 MB)")
        return bytes.decodeToString()
    }

    suspend fun delete(template: Template): DeleteResult = withContext(Dispatchers.IO) {
        val used = records.list().count { it.templateId == template.id && it.templateVersion == template.version }
        if (used > 0) return@withContext DeleteResult.InUse(used)
        val listing = projects.list().filter { p -> p.templates.any { it.id == template.id && it.version == template.version } }
        if (listing.isNotEmpty()) return@withContext DeleteResult.UsedByProjects(listing.map { it.code })
        store.delete(template.id, template.version)
        reload(store.list())
        DeleteResult.Deleted
    }

    private companion object {
        const val MAX_BYTES = 5 * 1024 * 1024
    }
}

package com.ncthanhngo.pollmaster.data

import android.content.Context
import com.ncthanhngo.pollmaster.core.model.Template
import com.ncthanhngo.pollmaster.core.storage.InstallResult
import com.ncthanhngo.pollmaster.core.storage.RecordStore
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
}

class TemplatesRepository(
    private val context: Context,
    private val store: TemplateStore,
    private val records: RecordStore,
    private val settings: Settings,
) {
    private val _templates = MutableStateFlow<List<Template>>(emptyList())
    val templates: StateFlow<List<Template>> = _templates.asStateFlow()

    suspend fun refresh() {
        _templates.value = withContext(Dispatchers.IO) { store.list() }
    }

    fun find(id: String, version: String): Template? = store.get(id, version)

    /** Installs bundled assets once per file. */
    suspend fun installBundled() = withContext(Dispatchers.IO) {
        val done = settings.installedBundled.toMutableSet()
        val names = context.assets.list("").orEmpty().filter { it.endsWith(".json") }
        for (name in names) {
            if (name in done) continue
            val text = context.assets.open(name).use { it.readBytes().decodeToString() }
            if (store.install(text, overwrite = false) !is InstallResult.Invalid) done += name
        }
        settings.installedBundled = done
        _templates.value = store.list()
    }

    suspend fun install(json: String, overwrite: Boolean): InstallResult = withContext(Dispatchers.IO) {
        store.install(json, overwrite).also { _templates.value = store.list() }
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
        store.delete(template.id, template.version)
        _templates.value = store.list()
        DeleteResult.Deleted
    }

    private companion object {
        const val MAX_BYTES = 5 * 1024 * 1024
    }
}

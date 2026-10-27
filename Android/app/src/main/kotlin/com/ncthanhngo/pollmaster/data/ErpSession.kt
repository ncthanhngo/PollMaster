package com.ncthanhngo.pollmaster.data

import android.content.Context
import com.ncthanhngo.pollmaster.core.format.nowIso
import com.ncthanhngo.pollmaster.core.model.PollJson
import com.ncthanhngo.pollmaster.core.project.ErpProjectSync
import com.ncthanhngo.pollmaster.core.project.TemplateRef
import com.ncthanhngo.pollmaster.core.storage.newestVersions
import com.ncthanhngo.pollmaster.core.storage.RecordStore
import com.ncthanhngo.pollmaster.platform.MediaProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/** ERP sign-in and the project list it brings (projects are not created on the device). */
class ErpSession(
    context: Context,
    private val root: File,
    private val client: ErpClient,
    private val projects: ProjectsRepository,
    private val records: RecordsRepository,
    private val templates: TemplatesRepository,
    private val recordStore: RecordStore,
    private val media: MediaProcessor,
) {
    private val prefs = context.getSharedPreferences("pollmaster_erp_account", Context.MODE_PRIVATE)

    private val _account = MutableStateFlow(stored())
    /** Signed-in ERP user; null shows the sign-in screen. */
    val account: StateFlow<ErpAccount?> = _account.asStateFlow()

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    private val _syncError = MutableStateFlow<String?>(null)
    /** Why the last ERP project fetch failed (the cached list is shown meanwhile). */
    val syncError: StateFlow<String?> = _syncError.asStateFlow()

    private fun stored(): ErpAccount? = if (!client.hasSession) null
    else prefs.getString(KEY_ACCOUNT, null)?.let { runCatching { PollJson.decodeFromString(ErpAccount.serializer(), it) }.getOrNull() }

    private fun setAccount(a: ErpAccount?) {
        _account.value = a
        prefs.edit().apply { if (a != null) putString(KEY_ACCOUNT, PollJson.encodeToString(ErpAccount.serializer(), a)) else remove(KEY_ACCOUNT) }.apply()
    }

    /** Projects and records made before ERP sign-in existed are wiped once (templates stay); true while that is still ahead. */
    fun localDataResetPending(): Boolean = !prefs.getBoolean(KEY_RESET, false) && recordStore.list().isNotEmpty()

    suspend fun login(email: String, password: String) {
        val a = client.login(email.trim(), password)
        if (!prefs.getBoolean(KEY_RESET, false)) {
            records.discardAll()
            withContext(Dispatchers.IO) { ErpProjectSync.resetLocalData(root) }
            prefs.edit().putBoolean(KEY_RESET, true).apply()
            projects.select(null)
        }
        setAccount(a)
        projects.refresh()
        records.refresh()
        sync()
    }

    /** Records stay on the device; projects the next account cannot see stay hidden. */
    suspend fun logout() {
        records.flushAll()
        client.logout()
        setAccount(null)
        projects.select(null)
    }

    /** Fetches the ERP project list (sign-in, app opened, pull to refresh). Offline the last list stays. */
    suspend fun sync() {
        if (_account.value == null || _syncing.value) return
        _syncing.value = true
        try {
            val list = client.projects()
            val library = newestVersions(templates.templates.value).map { TemplateRef(it.id, it.version) }
            val changes = withContext(Dispatchers.IO) { ErpProjectSync.apply(list, projects.store, nowIso(), library) }
            for (c in changes) {
                // a failed download leaves no file, so the next sync tries again
                val jpeg = c.url?.let { url -> runCatching { media.squareJpeg(client.file(url)) }.getOrNull() }
                withContext(Dispatchers.IO) { if (jpeg != null) projects.store.setAvatar(c.projectId, jpeg) else projects.store.removeAvatar(c.projectId) }
            }
            _syncError.value = null
            projects.refresh()
            records.refresh()
        } catch (e: ErpException) {
            // expired: back to the sign-in screen, which shows this message
            if (e.sessionExpired) setAccount(null)
            _syncError.value = if (e.sessionExpired) e.message else "Chưa tải được danh sách dự án từ ERP: ${e.message}"
        } catch (e: java.io.IOException) {
            _syncError.value = "Chưa tải được danh sách dự án từ ERP: không kết nối được máy chủ."
        } finally {
            _syncing.value = false
        }
    }

    private companion object {
        const val KEY_ACCOUNT = "account"
        const val KEY_RESET = "local_data_reset"
    }
}

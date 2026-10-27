package com.ncthanhngo.pollmaster.data

import android.content.Context
import com.ncthanhngo.pollmaster.core.format.nowIso
import com.ncthanhngo.pollmaster.core.model.PollJson
import com.ncthanhngo.pollmaster.core.project.ErpProjectSync
import com.ncthanhngo.pollmaster.core.project.TemplateRef
import com.ncthanhngo.pollmaster.core.storage.newestVersions
import com.ncthanhngo.pollmaster.platform.MediaProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
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
    private val media: MediaProcessor,
    private val backup: BackupService,
    private val scope: CoroutineScope,
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
    suspend fun localDataResetPending(): Boolean = withContext(Dispatchers.IO) {
        !prefs.getBoolean(KEY_RESET, false) && ErpProjectSync.localDataCounts(root).let { it.first + it.second > 0 }
    }

    /** What the sign-in screen asks before old data is wiped: counts, how many backups failed, and whether work is running. */
    data class WipePrompt(val records: Int, val projects: Int, val failed: Int = 0, val busy: Boolean = false)

    private val _wipePrompt = MutableStateFlow<WipePrompt?>(null)
    val wipePrompt: StateFlow<WipePrompt?> = _wipePrompt.asStateFlow()

    /** Account whose credentials were accepted while [wipePrompt] waits for the owner's answer. */
    private var pendingAccount: ErpAccount? = null

    @Volatile private var generation = 0
    @Volatile private var syncJob: Job? = null

    /** Checks the credentials. With old data on the device the sign-in waits for [backupThenWipe] / [wipeWithoutBackup] ([wipePrompt] is set). */
    suspend fun login(email: String, password: String) {
        val a = client.login(email.trim(), password)
        if (!prefs.getBoolean(KEY_RESET, false)) {
            val (recordCount, projectCount) = withContext(Dispatchers.IO) { ErpProjectSync.localDataCounts(root) }
            if (recordCount + projectCount > 0) {
                pendingAccount = a
                _wipePrompt.value = WipePrompt(recordCount, projectCount)
                return
            }
            prefs.edit().putBoolean(KEY_RESET, true).apply()
        }
        finishLogin(a)
    }

    /**
     * Backs up every record (needed or not) and wipes. Every record folder counts, so unreadable ones fail the backup; if any fails
     * nothing is deleted and [wipePrompt] reports the count. Runs in the session scope: a recreated screen cannot interrupt it.
     */
    suspend fun backupThenWipe() = inSessionScope {
        val prompt = _wipePrompt.value ?: return@inSessionScope
        _wipePrompt.value = prompt.copy(busy = true)
        try {
            val (done, _) = backup.backupAll()
            val failed = maxOf(prompt.records - done, 0)
            if (failed > 0) _wipePrompt.value = prompt.copy(failed = failed) else wipeAndFinish()
        } catch (e: Exception) {
            if (_wipePrompt.value != null) _wipePrompt.value = prompt.copy(failed = maxOf(prompt.records, 1))
            throw e
        }
    }

    suspend fun wipeWithoutBackup() = inSessionScope {
        _wipePrompt.value = _wipePrompt.value?.copy(busy = true) ?: return@inSessionScope
        wipeAndFinish()
    }

    private suspend fun inSessionScope(block: suspend () -> Unit) = scope.async { block() }.await()

    /** Owner declined: the credentials are forgotten and the sign-in screen stays. */
    fun cancelWipe() {
        pendingAccount = null
        _wipePrompt.value = null
        client.logout()
    }

    private suspend fun wipeAndFinish() {
        val a = pendingAccount ?: return
        withContext(NonCancellable) { // delete, mark done, close the prompt: always together
            records.discardAll() // no open editor or ticker may write a record back after the wipe
            try {
                withContext(Dispatchers.IO) { ErpProjectSync.resetLocalData(root) }
            } finally {
                prefs.edit().putBoolean(KEY_RESET, true).apply()
                projects.select(null)
                pendingAccount = null
                _wipePrompt.value = null
            }
        }
        finishLogin(a)
    }

    private suspend fun finishLogin(a: ErpAccount) {
        _syncError.value = null
        setAccount(a)
        projects.refresh()
        records.refresh()
        sync()
    }

    /** The refresh token was refused during a call outside the project sync: back to the sign-in screen. */
    fun expire() = setAccount(null)

    /** Records stay on the device; projects the next account cannot see stay hidden. In-flight project fetches are dropped. */
    fun logout() {
        records.releaseAll()
        generation++
        syncJob?.cancel()
        syncJob = null
        _syncing.value = false
        _syncError.value = null
        setAccount(null)
        client.logout()
        projects.select(null)
    }

    /** Fetches the ERP project list (sign-in, app opened, pull to refresh). Offline the last list stays. */
    suspend fun sync() {
        if (_account.value == null || !_syncing.compareAndSet(false, true)) return
        val gen = generation
        try {
            coroutineScope {
                val job = launch { fetchProjects(gen) }
                syncJob = job
                job.join()
            }
        } finally {
            if (gen == generation) { // a logout has already reset the flag for the next session
                _syncing.value = false
                syncJob = null
            }
        }
    }

    private suspend fun fetchProjects(gen: Int) {
        fun stale() = gen != generation
        try {
            val list = client.projects()
            if (stale()) return
            val library = newestVersions(templates.templates.value).map { TemplateRef(it.id, it.version) }
            val changes = withContext(Dispatchers.IO) { if (stale()) emptyList() else ErpProjectSync.apply(list, projects.store, nowIso(), library) }
            for (c in changes) {
                if (stale()) return
                // a failed download leaves no file, so the next sync tries again
                val jpeg = c.url?.let { url -> runCatching { client.file(url).let { bytes -> withContext(Dispatchers.IO) { media.squareJpeg(bytes) } } }.getOrNull() }
                withContext(Dispatchers.IO) { if (jpeg != null) projects.store.setAvatar(c.projectId, jpeg) else projects.store.removeAvatar(c.projectId) }
            }
            if (stale()) return
            _syncError.value = null
            projects.refresh()
            records.refresh()
        } catch (e: ErpException) {
            if (stale()) return // a deliberate sign-out must not show "expired"
            // expired: back to the sign-in screen, which shows this message
            if (e.sessionExpired) setAccount(null)
            _syncError.value = if (e.sessionExpired) e.message else "Chưa tải được danh sách dự án từ ERP: ${e.message}"
        } catch (e: java.io.IOException) {
            if (stale()) return
            _syncError.value = "Chưa tải được danh sách dự án từ ERP: không kết nối được máy chủ."
        }
    }

    private companion object {
        const val KEY_ACCOUNT = "account"
        const val KEY_RESET = "local_data_reset"
    }
}

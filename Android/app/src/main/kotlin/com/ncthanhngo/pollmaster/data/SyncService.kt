package com.ncthanhngo.pollmaster.data

import android.content.Context
import com.ncthanhngo.pollmaster.core.export.ExportBuilder
import com.ncthanhngo.pollmaster.core.model.Record
import com.ncthanhngo.pollmaster.core.project.ProjectStore
import com.ncthanhngo.pollmaster.core.project.ref
import com.ncthanhngo.pollmaster.core.storage.RecordStore
import com.ncthanhngo.pollmaster.core.sync.ConflictInfo
import com.ncthanhngo.pollmaster.core.sync.PendingDelete
import com.ncthanhngo.pollmaster.core.sync.PendingDeletes
import com.ncthanhngo.pollmaster.core.sync.RecordChange
import com.ncthanhngo.pollmaster.core.sync.SyncEngine
import com.ncthanhngo.pollmaster.core.sync.SyncHooks
import com.ncthanhngo.pollmaster.core.sync.downloadMedia
import com.ncthanhngo.pollmaster.core.sync.inspectConflict
import com.ncthanhngo.pollmaster.core.sync.keepMine
import com.ncthanhngo.pollmaster.core.sync.takeServer
import com.ncthanhngo.pollmaster.export.PdfExporter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** "Đang đồng bộ n/N phiếu" while a record sync pushes. */
data class SyncProgress(val done: Int, val total: Int)

/**
 * Record sync with the ERP (schema 10, phase 05): triggers, one run at a time, and the glue to open editors and the PDF maker.
 * Requests made during a run are folded into the next one; offline or signed out: ignored.
 */
class SyncService(
    context: Context,
    private val root: File,
    private val client: ErpClient,
    private val session: ErpSession,
    private val projects: ProjectsRepository,
    private val records: RecordsRepository,
    private val templates: TemplatesRepository,
    private val backup: BackupService,
    private val scope: CoroutineScope,
    private val online: () -> Boolean,
) {
    private val prefs = context.getSharedPreferences("pollmaster_sync", Context.MODE_PRIVATE)
    private val cacheDir = File(context.cacheDir, "erp-send")
    private val store = RecordStore(root)
    private val remote = ErpSyncRemote(client)
    private val lock = Any()
    private var queuedAll = false
    private val queued = HashSet<String>()
    private var job: Job? = null

    private val _progress = MutableStateFlow<SyncProgress?>(null)
    val progress: StateFlow<SyncProgress?> = _progress.asStateFlow()

    private val _issues = MutableStateFlow<Map<String, String>>(emptyMap())
    /** Short sentence per record the last sync could not push (e.g. "Tệp P05_01.jpg lớn hơn 95 MB."). */
    val issues: StateFlow<Map<String, String>> = _issues.asStateFlow()

    fun request(projectId: String? = null) {
        if (session.account.value == null) return
        synchronized(lock) {
            if (projectId == null) queuedAll = true else queued += projectId
            if (job?.isActive == true) return
            job = scope.launch { loop() }
        }
    }

    fun cancel() {
        synchronized(lock) { job?.cancel(); job = null; queuedAll = false; queued.clear() }
        _progress.value = null
    }

    /** All projects (null) or the ones asked for; null when nothing is queued (the loop then ends). */
    private fun take(): Set<String>? = synchronized(lock) {
        if (!queuedAll && queued.isEmpty()) { job = null; return null }
        (if (queuedAll) emptySet() else queued.toSet()).also { queuedAll = false; queued.clear() }
    }

    private suspend fun loop() {
        while (true) {
            val only = take() ?: break
            runOnce(only)
        }
        _progress.value = null
    }

    private fun engine() = SyncEngine(
        root, remote,
        SyncHooks(
            isOpen = { records.isOpen(it) },
            update = { id, change -> withContext(Dispatchers.IO) { records.updateForSync(id, change) } },
            makePdf = ::makePdf,
            progress = { done, total -> _progress.value = if (done < total) SyncProgress(done, total) else null },
        ),
    )

    private suspend fun runOnce(only: Set<String>) {
        val acct = session.account.value ?: return
        if (!online()) return
        try {
            withContext(Dispatchers.IO) { records.flushAll() }
            var userId = acct.userId
            if (userId == null) {
                userId = client.me()
                session.updateAccount(acct.copy(userId = userId))
            }
            val engine = engine()
            val all = withContext(Dispatchers.IO) { ProjectStore(root).list() }
            val removed = engine.prune(userId, prefs.getString(KEY_LAST_USER, null), all.filter { it.archived }.map { it.id }.toSet())
            prefs.edit().putString(KEY_LAST_USER, userId).apply()
            val ids = all.filter { !it.archived && (only.isEmpty() || it.id in only) }.map { it.id }
            val reports = engine.sync(ids, userId)
            val issues = _issues.value.toMutableMap()
            for ((pid, rep) in reports) {
                val mine = withContext(Dispatchers.IO) { store.list().filter { it.projectId == pid }.map { it.id }.toSet() }
                issues.keys.removeAll(mine)
                issues += rep.failures
            }
            _issues.value = issues
            projects.refresh()      // rights may have changed; both calls are cheap when nothing did
            records.refresh()
        } catch (e: CancellationException) {
            throw e
        } catch (e: ErpException) {
            if (e.sessionExpired) session.expire()
        } catch (_: IOException) {
            // offline or timed out: the next trigger tries again
        }
    }

    // ---- the user's answers to a conflict (ux-spec N); each returns an error sentence, null when done ----

    private fun failure(e: Exception) = if (e is IOException) "Không kết nối được máy chủ. Thử lại khi có mạng." else e.message ?: "Lỗi không rõ."

    /** What the dialog says; null when the server could not be asked (offline). */
    suspend fun conflictInfo(recordId: String): ConflictInfo? {
        val pid = withContext(Dispatchers.IO) { store.load(recordId)?.projectId } ?: return null
        return try { engine().inspectConflict(pid, recordId) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
    }

    /** "Giữ bản của tôi". */
    suspend fun keepMine(recordId: String): String? {
        withContext(Dispatchers.IO) { records.flushEditor(recordId) }
        val pid = withContext(Dispatchers.IO) { store.load(recordId)?.projectId } ?: return "Không tìm thấy phiếu trên máy."
        return try { engine().keepMine(pid, recordId); records.refresh(); null } catch (e: CancellationException) { throw e } catch (e: Exception) { failure(e) }
    }

    /** "Lấy bản máy chủ": the device version is zipped first, then replaced. */
    suspend fun takeServer(recordId: String): String? {
        withContext(Dispatchers.IO) { records.flushEditor(recordId) }
        val pid = withContext(Dispatchers.IO) { store.load(recordId)?.projectId } ?: return "Không tìm thấy phiếu trên máy."
        if (!backup.backupConflictCopy(recordId)) return "Không sao lưu được bản trên máy. Bản máy chủ chưa được lấy."
        return try {
            engine().takeServer(pid, recordId)
            records.reloadFromStore(recordId)
            records.refresh()
            null
        } catch (e: CancellationException) { throw e } catch (e: Exception) { failure(e) }
    }

    /** "Khôi phục" a record the server lost: it goes up again as a new one. */
    suspend fun restore(recordId: String) {
        withContext(Dispatchers.IO) { records.updateForSync(recordId) { it.copy(serverRev = null, syncConflict = false) } }
        store.load(recordId)?.projectId?.let(::request)
    }

    /** The change log of a record (needs the network). */
    suspend fun changes(projectId: String, recordId: String): List<RecordChange> = remote.changes(projectId, recordId)

    /** PDF for the web view of a pushed record; null when it cannot be made (the push itself is not affected). */
    private suspend fun makePdf(record: Record): File? = withContext(Dispatchers.IO) {
        runCatching {
            val template = templates.find(record.templateId, record.templateVersion) ?: return@runCatching null
            cacheDir.mkdirs()
            val file = File(cacheDir, "${record.id}.pdf")
            file.outputStream().buffered().use { out ->
                PdfExporter(store.dir(record.id)).write(ExportBuilder(template, record, projects.get(record.projectId)?.ref()).document(), out)
            }
            file
        }.getOrNull()
    }

    /** A record opened in the editor: whatever is still missing from the server copy (videos included) is fetched. */
    fun fetchMediaOnOpen(record: Record) {
        val pid = record.projectId ?: return
        if (session.account.value == null || !online() || record.serverRev == null) return
        scope.launch { runCatching { engine().downloadMedia(pid, record.id) } }
    }

    /** The device copy was deleted: the server copy goes too (queued until there is a network). */
    fun queueRemoteDelete(r: Record) {
        val pid = r.projectId ?: return
        if (r.serverRev == null && r.erpUploadedAt == null) return
        PendingDeletes(root).add(PendingDelete(pid, r.id))
        request(pid)
    }

    private companion object {
        const val KEY_LAST_USER = "last_user_id"
    }
}

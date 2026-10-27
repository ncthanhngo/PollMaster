package com.ncthanhngo.pollmaster.core.sync

import com.ncthanhngo.pollmaster.core.model.Record
import com.ncthanhngo.pollmaster.core.project.ProjectStore
import com.ncthanhngo.pollmaster.core.storage.RecordStore
import com.ncthanhngo.pollmaster.core.storage.TemplateStore
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.time.OffsetDateTime

/** Connections the engine needs from the app: the open editors and the PDF maker. Defaults suit tests and headless use. */
class SyncHooks(
    /** A record open in an editor is never overwritten by a pull; its sync is retried after the editor closes. */
    val isOpen: suspend (String) -> Boolean = { false },
    /**
     * Applies `change` to the current copy of a record (the open editor's, else the stored one) and saves it; `change` returns null to
     * leave it alone. Returns the record as saved, null when it is gone. Null hook = work on the stored file.
     */
    val update: (suspend (String, (Record) -> Record?) -> Record?)? = null,
    /** PDF of a record for the web view (best effort). */
    val makePdf: (suspend (Record) -> File?)? = null,
    val progress: (done: Int, total: Int) -> Unit = { _, _ -> },
)

/** What one project's run did. [failures] maps record id to a short sentence for the user. */
class SyncReport {
    var created = 0; var overwritten = 0; var pushed = 0; var conflicts = 0; var deletedLocal = 0; var deletedRemote = 0
    val failures = LinkedHashMap<String, String>()

    /** The server refused the project as a whole (no access). */
    var projectError: String? = null

    /** Something was left for later (open record, unreadable copy, media): the pull cursor stays where it was. */
    var incomplete = false
}

/** The record sync of schema 10 / phase 05: pull -> decide -> push -> pending deletes -> media. One run per project at a time. */
class SyncEngine(
    root: File,
    internal val remote: SyncRemote,
    internal val hooks: SyncHooks = SyncHooks(),
    internal val now: () -> OffsetDateTime = { OffsetDateTime.now() },
) {
    internal val records = RecordStore(root)
    internal val projects = ProjectStore(root)
    internal val templates = TemplateStore(root)
    internal val pending = PendingDeletes(root)

    /**
     * Removes what the account no longer owns: clean records of another account ([lastUserId] differs from [me]) and clean records of
     * projects the account lost. Returns the removed ids.
     */
    suspend fun prune(me: String, lastUserId: String?, lostProjects: Set<String>): List<String> {
        val all = records.list()
        val ids = sortedSetOf<String>()
        if (lastUserId != null && lastUserId != me) ids += SyncPlanner.staleAfterAccountSwitch(all.map(SyncPlanner::Local), me)
        for (p in lostProjects) ids += SyncPlanner.lostWithProject(all.filter { it.projectId == p }.map(SyncPlanner::Local))
        return ids.filter { !hooks.isOpen(it) && records.delete(it) }
    }

    /** Syncs the projects one after the other. A record-level problem lands in the report; anything that stops everything is thrown. */
    suspend fun sync(projectIds: List<String>, me: String): Map<String, SyncReport> {
        val byProject = records.list().groupBy { it.projectId.orEmpty() }
        val out = LinkedHashMap<String, SyncReport>()
        for (pid in projectIds) {
            currentCoroutineContext().ensureActive()
            out[pid] = syncProject(pid, me, byProject[pid].orEmpty())
        }
        return out
    }

    private class Pulled(val serverTime: String, val canPost: Boolean, val canManage: Boolean, val records: List<SyncRecord>, val ids: List<SyncRef>)

    internal suspend fun syncProject(pid: String, me: String, local: List<Record>): SyncReport {
        val rep = SyncReport()
        val project = projects.get(pid)?.takeIf { !it.archived } ?: return rep
        val pull = try { pullAll(pid, project.recordsSyncedAt) } catch (e: SyncRecordFailure) { rep.projectError = e.message; return rep }
        if (project.canPost != pull.canPost || project.canManage != pull.canManage) {
            projects.save(project.copy(canPost = pull.canPost, canManage = pull.canManage))
        }
        val waiting = pending.load().filter { it.projectId == pid }.map { it.recordId }.toSet()
        val byId = local.associateBy { it.id }.toMutableMap()
        val plan = SyncPlanner.plan(
            local.map(SyncPlanner::Local), pull.records.map { SyncRef(it.recordId, it.rev) }, pull.ids, waiting, me, pull.canPost, pull.canManage,
        )
        val served = pull.records.associateBy { it.recordId }
        val toPush = mutableListOf<String>()
        for ((id, action) in plan.entries.sortedBy { it.key }) {
            if (action == SyncPlanner.Action.Push) { toPush += id; continue }
            if (hooks.isOpen(id)) { rep.incomplete = true; continue }
            try {
                when (action) {
                    SyncPlanner.Action.Create, SyncPlanner.Action.Overwrite -> {
                        val s = served[id] ?: continue
                        var r = SyncApply.record(s, pid)
                        byId[id]?.let { r = SyncApply.overwrite(it, r) }
                        records.save(r)
                        pruneMedia(r)
                        byId[id] = r
                        if (action == SyncPlanner.Action.Create) rep.created++ else rep.overwritten++
                        if (!fetchMedia(pid, r, s.media, includeVideo = false)) rep.incomplete = true
                    }
                    SyncPlanner.Action.Conflict -> {
                        byId[id] = byId.getValue(id).copy(syncConflict = true)
                        update(id) { it.copy(syncConflict = true) }
                        rep.conflicts++
                    }
                    SyncPlanner.Action.DeleteLocal -> { records.delete(id); byId.remove(id); rep.deletedLocal++ }
                    SyncPlanner.Action.Push -> Unit
                }
            } catch (f: SyncRecordFailure) { rep.failures[id] = f.message.orEmpty(); rep.incomplete = true }
        }
        for ((i, id) in toPush.withIndex()) {
            currentCoroutineContext().ensureActive()
            hooks.progress(i, toPush.size)
            try {
                when (push(id, project)) {
                    PushOutcome.Pushed -> rep.pushed++
                    PushOutcome.Conflict -> rep.conflicts++
                    PushOutcome.Replaced -> rep.overwritten++
                    PushOutcome.Skipped -> Unit
                }
            } catch (f: SyncRecordFailure) { rep.failures[id] = f.message.orEmpty() }
        }
        if (toPush.isNotEmpty()) hooks.progress(toPush.size, toPush.size)
        for (item in pending.load().filter { it.projectId == pid }) {
            try {
                remote.delete(pid, item.recordId)
                pending.remove(item)
                rep.deletedRemote++
            } catch (f: SyncRecordFailure) { rep.failures[item.recordId] = f.message.orEmpty() }
        }
        for (r in byId.values.filter { it.serverRev != null && !it.isSyncDirty && !it.syncConflict }) {
            if (!fetchMedia(pid, r, includeVideo = false)) { rep.incomplete = true; break }
        }
        if (!rep.incomplete) projects.get(pid)?.let { projects.save(it.copy(recordsSyncedAt = pull.serverTime)) }
        return rep
    }

    /** Every page of `poll-sync` since the project's cursor. `ids` and `server_time` come from the first page. */
    private suspend fun pullAll(pid: String, since: String?): Pulled {
        val first = remote.pull(pid, since, null)
        val all = first.records.toMutableList()
        val seen = HashSet<String>()
        var next = first.nextCursor
        while (!next.isNullOrEmpty() && seen.add(next)) {
            currentCoroutineContext().ensureActive()
            val page = remote.pull(pid, since, next)
            all += page.records
            next = page.nextCursor
        }
        return Pulled(first.serverTime, first.canPost, first.canManage, all, first.ids)
    }

    internal suspend fun update(id: String, change: (Record) -> Record?): Record? {
        hooks.update?.let { return it(id, change) }
        val r = records.load(id) ?: return null
        val next = change(r) ?: return r
        records.save(next)
        return next
    }

    internal fun pruneMedia(r: Record) {
        val used = SyncApply.mediaFiles(r).map { it.substringAfterLast('/') }.toSet()
        records.mediaDir(r.id).listFiles().orEmpty().filter { it.name !in used }.forEach { it.delete() }
    }
}

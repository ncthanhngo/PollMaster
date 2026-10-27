package com.ncthanhngo.pollmaster.core.sync

import com.ncthanhngo.pollmaster.core.format.parseInstant
import com.ncthanhngo.pollmaster.core.model.Record
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/** Has changes the server does not know yet (schema 10.1): never uploaded, or edited after the last upload / pull. */
val Record.isSyncDirty: Boolean
    get() {
        val sent = parseInstant(erpUploadedAt) ?: return true
        val updated = parseInstant(updatedAt) ?: return true
        return updated.isAfter(sent)
    }

/** The pure decisions of a sync run (schema 10, ux-spec N). Same rules on iOS and Android: both run `shared/sync-test-vectors.json`. */
object SyncPlanner {
    enum class Action(val key: String) { Create("create"), Overwrite("overwrite"), Conflict("conflict"), Push("push"), DeleteLocal("deleteLocal") }

    /** What the decisions need to know about a record on the device. */
    data class Local(val id: String, val createdBy: String?, val serverRev: Int?, val conflict: Boolean, val dirty: Boolean) {
        constructor(r: Record) : this(r.id, r.createdBy, r.serverRev, r.syncConflict, r.isSyncDirty)
    }

    /**
     * One action per record id; ids not in the result need nothing. [changed] = records the pull returned, [ids] = every record the
     * server has in the project, [deleted] = ids waiting in pending-deletes (never brought back).
     */
    fun plan(
        local: List<Local>, changed: List<SyncRef>, ids: List<SyncRef>, deleted: Set<String>,
        me: String, canPost: Boolean, canManage: Boolean,
    ): Map<String, Action> {
        val changedRev = HashMap<String, Int>()
        for (c in changed) changedRev[c.recordId] = maxOf(changedRev[c.recordId] ?: c.rev, c.rev)
        val onServer = ids.map { it.recordId }.toSet()
        val out = HashMap<String, Action>()
        for (l in local) {
            if (l.conflict) continue // waits for the user's choice
            val mine = l.serverRev
            if (mine != null) {
                val theirs = changedRev[l.id]
                if (theirs != null && theirs > mine) { out[l.id] = if (l.dirty) Action.Conflict else Action.Overwrite; continue }
                if (l.id !in onServer) { out[l.id] = if (l.dirty) Action.Conflict else Action.DeleteLocal; continue }
            }
            val mayEdit = canPost && (l.createdBy == null || l.createdBy == me || canManage)
            if (mayEdit && (mine == null || l.dirty)) out[l.id] = Action.Push
        }
        val have = local.map { it.id }.toSet()
        for (id in changedRev.keys) if (id !in have && id !in deleted) out[id] = Action.Create
        return out
    }

    /** Clean records of another account: removed when a different account signs in (pulled again if visible). Dirty ones stay. */
    fun staleAfterAccountSwitch(local: List<Local>, me: String): List<String> =
        local.filter { !it.dirty && it.createdBy != null && it.createdBy != me }.map { it.id }

    /** Clean records of a project the account can no longer see. */
    fun lostWithProject(local: List<Local>): List<String> = local.filter { !it.dirty }.map { it.id }

    private val bookkeeping = setOf(
        "updatedAt", "lastPosition", "backupAt", "erpUploadedAt", "serverRev", "syncConflict", "createdBy", "createdByName", "seq",
    )

    /** True when two record.json contents are the same apart from bookkeeping keys (and keys holding null). */
    fun sameContent(a: JsonElement, b: JsonElement): Boolean = canonical(a, true) == canonical(b, true)

    private fun canonical(v: JsonElement, top: Boolean = false): JsonElement = when (v) {
        is JsonObject -> JsonObject(v.filter { (k, x) -> !(top && k in bookkeeping) && x !is JsonNull }.mapValues { canonical(it.value) })
        is JsonArray -> JsonArray(v.map { canonical(it) })
        else -> canonicalNumber(v)
    }

    /** `3` and `3.0` are the same number. */
    private fun canonicalNumber(v: JsonElement): JsonElement {
        val p = v as? kotlinx.serialization.json.JsonPrimitive ?: return v
        if (p.isString) return v
        val d = p.content.toDoubleOrNull() ?: return v
        return if (d == Math.rint(d) && Math.abs(d) < 1e15) kotlinx.serialization.json.JsonPrimitive(d.toLong()) else v
    }

    /** Videos are downloaded only when the record is opened; photos, audio and signatures at once. */
    fun isVideo(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in setOf("mp4", "mov", "m4v", "avi", "mkv", "webm")
}

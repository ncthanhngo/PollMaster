package com.ncthanhngo.pollmaster.core.sync

import com.ncthanhngo.pollmaster.core.model.Record
import com.ncthanhngo.pollmaster.core.project.Project
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Who may do what with a record (schema 10.4, ux-spec N). Pure; shared vectors on both platforms. */
object RecordAccess {
    /** `canPost ∧ (createdBy absent ∨ mine ∨ canManage)`. A record without `createdBy` was made on this device by the signed-in user. */
    fun canEdit(createdBy: String?, me: String?, canPost: Boolean, canManage: Boolean): Boolean =
        canPost && (createdBy == null || createdBy == me || canManage)

    fun canEdit(r: Record, me: String?, project: Project?): Boolean =
        canEdit(r.createdBy, me, project?.canPost ?: true, project?.canManage ?: false)

    /** Unsent changes of another account (left on a shared device): hidden from the list unless the user manages the project. */
    fun isHiddenLeftover(createdBy: String?, me: String?, dirty: Boolean, canManage: Boolean): Boolean =
        dirty && createdBy != null && createdBy != me && !canManage
}

/** The sync label of a record card (ux-spec N): conflict, then error, then waiting, else synced. */
sealed interface RecordSyncState {
    data class Synced(val at: Instant?) : RecordSyncState
    data object Pending : RecordSyncState
    data object Conflict : RecordSyncState
    data class Failed(val message: String) : RecordSyncState

    /** "Đã đồng bộ dd-MM HH:mm" · "Chờ đồng bộ" · "Xung đột" · "Lỗi: …" */
    fun text(zone: ZoneId = ZoneId.systemDefault()): String = when (this) {
        is Synced -> at?.let { "Đã đồng bộ " + DateTimeFormatter.ofPattern("dd-MM HH:mm").withZone(zone).format(it) } ?: "Đã đồng bộ"
        Pending -> "Chờ đồng bộ"
        Conflict -> "Xung đột"
        is Failed -> "Lỗi: $message"
    }

    /** Stable name used by the shared vectors. */
    val kind: String
        get() = when (this) { is Synced -> "synced"; Pending -> "pending"; Conflict -> "conflict"; is Failed -> "failed" }

    companion object {
        fun of(dirty: Boolean, conflict: Boolean, failure: String?, syncedAt: Instant?): RecordSyncState = when {
            conflict -> Conflict
            failure != null -> Failed(failure)
            dirty -> Pending
            else -> Synced(syncedAt)
        }

        fun of(r: Record, failure: String?): RecordSyncState =
            of(r.isSyncDirty, r.syncConflict, failure, com.ncthanhngo.pollmaster.core.format.parseInstant(r.erpUploadedAt))
    }
}

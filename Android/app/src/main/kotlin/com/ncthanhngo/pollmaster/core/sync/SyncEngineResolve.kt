package com.ncthanhngo.pollmaster.core.sync

import com.ncthanhngo.pollmaster.core.format.parseInstant
import com.ncthanhngo.pollmaster.core.format.recordTitle
import com.ncthanhngo.pollmaster.core.project.Project
import java.time.Instant

/** What is known about a record flagged `syncConflict` (shown in the conflict dialog). */
sealed interface ConflictInfo {
    /** Someone saved a newer revision; who and when are null when the log could not be read. */
    data class Edited(val by: String?, val at: Instant?) : ConflictInfo

    /** The server no longer has the record. */
    data object Deleted : ConflictInfo
}

private fun SyncEngine.projectOf(id: String): Project =
    projects.get(id)?.takeIf { !it.archived } ?: throw SyncRecordFailure("Không tìm thấy dự án trên máy.")

suspend fun SyncEngine.inspectConflict(projectId: String, recordId: String): ConflictInfo {
    val project = projectOf(projectId)
    val page = remote.pull(projectId, project.recordsSyncedAt, null)
    if (page.ids.none { it.recordId == recordId }) return ConflictInfo.Deleted
    val latest = try { remote.changes(projectId, recordId).firstOrNull() } catch (_: Exception) { null }
    return ConflictInfo.Edited(latest?.changedByName?.takeIf { it.isNotEmpty() }, parseInstant(latest?.changedAt))
}

/** "Giữ bản của tôi": pushes the device copy over the server's (logged as a normal save). The stored copy must be current. */
suspend fun SyncEngine.keepMine(projectId: String, recordId: String) {
    when (push(recordId, projectOf(projectId), force = true)) {
        PushOutcome.Pushed, PushOutcome.Replaced -> Unit
        PushOutcome.Conflict -> throw SyncRecordFailure("Phiếu vừa được sửa lại trên máy chủ. Thử lại.")
        PushOutcome.Skipped -> throw SyncRecordFailure("Không tìm thấy phiếu trên máy.")
    }
}

/**
 * "Lấy bản máy chủ": replaces the device copy with the server's. The caller backs the device copy up first.
 * The server copy is read by a push that cannot win (`base_rev` 0): a record that exists answers 409 with its content.
 */
suspend fun SyncEngine.takeServer(projectId: String, recordId: String): com.ncthanhngo.pollmaster.core.model.Record {
    val project = projectOf(projectId)
    val local = records.load(recordId) ?: throw SyncRecordFailure("Không tìm thấy phiếu trên máy.")
    val template = templates.get(local.templateId, local.templateVersion)
    val probe = SyncPutBody(
        baseRev = 0, title = template?.let { recordTitle(it, local) } ?: "Phiếu", templateTitle = template?.title ?: local.templateId,
        media = emptyMap(), data = SyncApply.pushData(local),
    )
    return when (val r = remote.put(projectId, recordId, probe)) {
        is SyncPutResult.Conflict -> {
            val next = SyncApply.overwrite(local, SyncApply.record(r.current, projectId))
            records.save(next)
            pruneMedia(next)
            fetchMedia(projectId, next, r.current.media, includeVideo = false)
            next
        }
        is SyncPutResult.Ok -> { // identical content: nothing to take
            val started = now()
            update(recordId) { SyncApply.pushed(it, project.id, r.record, started) } ?: local
        }
        is SyncPutResult.MediaMissing -> throw SyncRecordFailure("Phiếu đã bị xoá trên máy chủ.")
    }
}

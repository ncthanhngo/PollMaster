package com.ncthanhngo.pollmaster.core.sync

import com.ncthanhngo.pollmaster.core.format.toIso
import com.ncthanhngo.pollmaster.core.model.PollJson
import com.ncthanhngo.pollmaster.core.model.Record
import com.ncthanhngo.pollmaster.core.model.asObject
import com.ncthanhngo.pollmaster.core.model.asText
import com.ncthanhngo.pollmaster.core.model.mediaItems
import kotlinx.serialization.json.JsonObject
import java.time.OffsetDateTime

/** How a record changes when a server copy arrives or a push succeeds. Pure; the engine and the open editor share it. */
object SyncApply {
    /** Keys that describe the device, not the survey: left out of what is pushed. */
    private val deviceOnly = listOf("erpUploadedAt", "serverRev", "syncConflict", "createdBy", "createdByName", "backupAt", "lastPosition")

    /** A clean local record from a server copy (`erpUploadedAt = updatedAt`). */
    fun record(s: SyncRecord, projectId: String): Record {
        val r = try {
            PollJson.decodeFromJsonElement(Record.serializer(), s.data)
        } catch (e: Exception) {
            throw SyncRecordFailure("Không đọc được phiếu từ máy chủ.")
        }
        if (r.id != s.recordId) throw SyncRecordFailure("Phiếu trên máy chủ có mã không khớp.")
        return r.copy(
            projectId = projectId, seq = s.seq ?: r.seq, serverRev = s.rev, createdBy = s.createdBy, createdByName = s.createdByName,
            syncConflict = false, backupAt = null, lastPosition = null, erpUploadedAt = r.updatedAt,
        )
    }

    /** The server copy replaces a clean local record; where the user last worked and the backup stamp stay. */
    fun overwrite(local: Record, server: Record): Record = server.copy(lastPosition = local.lastPosition, backupAt = local.backupAt)

    /** record.json as sent to the server. */
    fun pushData(r: Record): JsonObject {
        val o = PollJson.encodeToJsonElement(Record.serializer(), r).asObject() ?: throw SyncRecordFailure("Không đọc được phiếu.")
        return JsonObject(o.filterKeys { it !in deviceOnly })
    }

    /**
     * After the server took a push that started at [at]: remember rev, number and creator. The stamp is a second early so an edit made
     * during the push still counts as newer. Null (leave the record alone) when it was moved to another project meanwhile.
     */
    fun pushed(r: Record, projectId: String, s: SyncRecord, at: OffsetDateTime): Record? {
        if (r.projectId != projectId) return null
        return r.copy(
            serverRev = s.rev, seq = s.seq ?: r.seq, createdBy = s.createdBy ?: r.createdBy, createdByName = s.createdByName ?: r.createdByName,
            syncConflict = false, erpUploadedAt = toIso(at.minusSeconds(1)),
        )
    }

    /** Relative paths (`media/x.jpg`) of every media/signature file a record references. */
    fun mediaFiles(r: Record): List<String> {
        fun files(v: com.ncthanhngo.pollmaster.core.model.FieldValue): List<String> =
            v.media.map { it.file } + v.mediaItems().map { it.file } + listOfNotNull(v.v.asObject()?.get("file").asText())
        return r.values.values.flatMap(::files) + r.tables.values.flatMap { rows -> rows.flatMap { row -> row.values.values.flatMap(::files) } } +
            r.signatures.values.map { it.file }
    }
}

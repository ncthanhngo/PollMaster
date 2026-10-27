package com.ncthanhngo.pollmaster.core.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import java.io.File

/** Wire types of the record sync API (backend `project-polls.md`, schema 10.2). */
@Serializable
data class SyncMediaInfo(val name: String, val sha256: String = "", val size: Long = 0)

@Serializable
data class SyncRecord(
    @SerialName("record_id") val recordId: String,
    val rev: Int,
    val seq: Int? = null,
    @SerialName("created_by") val createdBy: String? = null,
    @SerialName("created_by_name") val createdByName: String? = null,
    val data: JsonElement,
    val media: List<SyncMediaInfo> = emptyList(),
    @SerialName("updated_by_name") val updatedByName: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
data class SyncRef(@SerialName("record_id") val recordId: String, val rev: Int)

/** One page of `GET poll-sync`. `ids` is filled on the first page only; [nextCursor] is empty/null on the last page. */
@Serializable
data class SyncPage(
    @SerialName("server_time") val serverTime: String,
    @SerialName("can_post") val canPost: Boolean = false,
    @SerialName("can_manage") val canManage: Boolean = false,
    val records: List<SyncRecord> = emptyList(),
    val ids: List<SyncRef> = emptyList(),
    @SerialName("next_cursor") val nextCursor: String? = null,
)

/** Body of `PUT poll-records/:recordId`. */
@Serializable
data class SyncPutBody(
    @SerialName("base_rev") val baseRev: Int,
    val title: String,
    @SerialName("record_code") val recordCode: String? = null,
    @SerialName("template_title") val templateTitle: String,
    /** `"media/x.jpg"` -> lower-case sha256. */
    val media: Map<String, String>,
    val data: JsonElement,
)

sealed interface SyncPutResult {
    data class Ok(val record: SyncRecord) : SyncPutResult

    /** 409 `REV_CONFLICT`: the server copy. */
    data class Conflict(val current: SyncRecord) : SyncPutResult

    /** 409 `MEDIA_MISSING`: names (`media/…`) the server lacks. */
    data class MediaMissing(val names: List<String>) : SyncPutResult
}

/** One field change of a log entry (`path` format: backend `project-polls.md`, schema 10.3). [label] is the server's, often empty. */
@Serializable
data class FieldChange(
    val path: String,
    val label: String = "",
    val old: JsonElement = JsonNull,
    val new: JsonElement = JsonNull,
)

/** One save of a record in the change log (`GET poll-records/:id/changes`, newest first). */
@Serializable
data class RecordChange(
    val rev: Int,
    @SerialName("changed_by_name") val changedByName: String = "",
    @SerialName("changed_at") val changedAt: String = "",
    val source: String = "app",
    val created: Boolean = false,
    val changes: List<FieldChange> = emptyList(),
)

/** A problem of one record (shown on it as "Lỗi: …"); the rest of the run goes on. Any other exception ends the run (offline, expired). */
class SyncRecordFailure(message: String) : Exception(message)

/** The ERP side of a sync run. The app implements it over HTTP; tests use a fake. */
interface SyncRemote {
    suspend fun pull(projectId: String, since: String?, cursor: String?): SyncPage
    suspend fun put(projectId: String, recordId: String, body: SyncPutBody): SyncPutResult
    suspend fun putMedia(projectId: String, recordId: String, name: String, file: File, sha256: String)

    /** Writes the file to [dest]. */
    suspend fun getMedia(projectId: String, recordId: String, name: String, dest: File)
    suspend fun putPdf(projectId: String, recordId: String, file: File)

    /** 204 and 404 both return normally. */
    suspend fun delete(projectId: String, recordId: String)

    /** The record's change log, newest first. */
    suspend fun changes(projectId: String, recordId: String): List<RecordChange>
}

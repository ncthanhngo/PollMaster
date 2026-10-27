package com.ncthanhngo.pollmaster.data

import com.ncthanhngo.pollmaster.core.model.PollJson
import com.ncthanhngo.pollmaster.core.project.ErpMessages
import com.ncthanhngo.pollmaster.core.sync.RecordChange
import com.ncthanhngo.pollmaster.core.sync.SyncPage
import com.ncthanhngo.pollmaster.core.sync.SyncPutBody
import com.ncthanhngo.pollmaster.core.sync.SyncPutResult
import com.ncthanhngo.pollmaster.core.sync.SyncRecord
import com.ncthanhngo.pollmaster.core.sync.SyncRecordFailure
import com.ncthanhngo.pollmaster.core.sync.SyncRemote
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.URLEncoder

/** The record sync calls of the ERP backend (`project-polls.md`), over [ErpClient]'s token handling. */
class ErpSyncRemote(private val client: ErpClient) : SyncRemote {
    private fun root(pid: String, rest: String) = "projects/$pid/$rest"

    private fun record(pid: String, id: String, rest: String = "") = root(pid, "poll-records/$id$rest")

    private fun enc(name: String) = URLEncoder.encode(name, "UTF-8").replace("+", "%20")

    private fun envelope(text: String): JsonObject? = runCatching { PollJson.parseToJsonElement(text).jsonObject }.getOrNull()

    /** A record-level answer (not found / refused / too big) becomes a [SyncRecordFailure]; anything else stops the run. */
    private fun fail(r: ErpClient.RawResponse, fallback: String): Exception {
        val message = envelope(r.text)?.get("error")?.jsonPrimitive?.contentOrNull?.takeIf(ErpMessages::isUserSentence)
        return if (r.status in listOf(400, 403, 404, 413)) SyncRecordFailure(message ?: fallback)
        else ErpException(message ?: "Máy chủ ERP báo lỗi ${r.status}.")
    }

    private inline fun <reified T> data(text: String, ser: kotlinx.serialization.KSerializer<T>): T {
        val d = envelope(text)?.get("data") ?: throw ErpException("Máy chủ ERP trả về dữ liệu không đọc được.")
        return runCatching { PollJson.decodeFromJsonElement(ser, d) }.getOrNull() ?: throw ErpException("Máy chủ ERP trả về dữ liệu không đọc được.")
    }

    override suspend fun pull(projectId: String, since: String?, cursor: String?): SyncPage {
        val q = buildMap { put("limit", "50"); since?.let { put("since", it) }; cursor?.let { put("cursor", it) } }
        val r = client.rawAuthorized("GET", root(projectId, "poll-sync"), q)
        if (r.status != 200) throw fail(r, "Không tìm thấy dự án trên ERP hoặc tài khoản không có quyền xem phiếu.")
        return data(r.text, SyncPage.serializer())
    }

    override suspend fun get(projectId: String, recordId: String): SyncRecord? {
        val r = client.rawAuthorized("GET", record(projectId, recordId))
        if (r.status == 404) return null
        if (r.status != 200) throw fail(r, "Không đọc được phiếu trên máy chủ.")
        return data(r.text, SyncRecord.serializer())
    }

    override suspend fun put(projectId: String, recordId: String, body: SyncPutBody): SyncPutResult {
        val r = client.rawAuthorized("PUT", record(projectId, recordId), json = PollJson.encodeToString(SyncPutBody.serializer(), body), timeoutMs = 60_000)
        when (r.status) {
            200 -> return SyncPutResult.Ok(data(r.text, SyncRecord.serializer()))
            409 -> {
                val e = envelope(r.text)
                val d = e?.get("data") as? JsonObject
                when (e?.get("err_key")?.jsonPrimitive?.contentOrNull) {
                    "REV_CONFLICT" -> d?.get("current")?.let { return SyncPutResult.Conflict(PollJson.decodeFromJsonElement(SyncRecord.serializer(), it)) }
                    "MEDIA_MISSING" -> (d?.get("missing") as? JsonArray)?.let { a -> return SyncPutResult.MediaMissing(a.map { it.jsonPrimitive.content }) }
                }
                throw ErpException("Máy chủ ERP trả về dữ liệu không đọc được.")
            }
            else -> throw fail(r, "Không có quyền sửa phiếu này hoặc phiếu không hợp lệ.")
        }
    }

    override suspend fun putMedia(projectId: String, recordId: String, name: String, file: File, sha256: String) {
        val r = client.rawAuthorized(
            "PUT", record(projectId, recordId, "/media/${enc(name)}"), upload = file,
            headers = mapOf("Content-Type" to "application/octet-stream", "X-Content-SHA256" to sha256), timeoutMs = 300_000,
        )
        if (r.status !in 200..299) throw fail(r, if (r.status == 413) "Tệp $name lớn hơn 95 MB." else "Không gửi được tệp $name.")
    }

    override suspend fun getMedia(projectId: String, recordId: String, name: String, dest: File) {
        val r = client.rawAuthorized("GET", record(projectId, recordId, "/media/${enc(name)}"), download = dest, timeoutMs = 300_000)
        if (r.status != 200) throw fail(r, "Không tải được tệp $name.")
    }

    override suspend fun putPdf(projectId: String, recordId: String, file: File) {
        val r = client.rawAuthorized("PUT", record(projectId, recordId, "/pdf"), upload = file, headers = mapOf("Content-Type" to "application/pdf"), timeoutMs = 120_000)
        if (r.status !in 200..299) throw fail(r, "Không gửi được PDF.")
    }

    override suspend fun delete(projectId: String, recordId: String) {
        val r = client.rawAuthorized("DELETE", record(projectId, recordId))
        if (r.status !in 200..299 && r.status != 404) throw fail(r, "Không xoá được phiếu trên máy chủ.")
    }

    override suspend fun changes(projectId: String, recordId: String): List<RecordChange> {
        val r = client.rawAuthorized("GET", record(projectId, recordId, "/changes"))
        if (r.status != 200) throw fail(r, "Không có quyền xem lịch sử hoặc phiếu chưa có trên máy chủ.")
        return data(r.text, ListSerializer(RecordChange.serializer()))
    }
}

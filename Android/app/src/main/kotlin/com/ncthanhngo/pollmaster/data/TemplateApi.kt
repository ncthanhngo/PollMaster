package com.ncthanhngo.pollmaster.data

import com.ncthanhngo.pollmaster.core.model.PollJson
import com.ncthanhngo.pollmaster.core.project.ErpMessages
import com.ncthanhngo.pollmaster.core.sync.ProjectTemplatesInfo
import com.ncthanhngo.pollmaster.core.sync.TemplateRemote
import com.ncthanhngo.pollmaster.core.sync.TemplateSyncFailure
import com.ncthanhngo.pollmaster.core.sync.TemplateVersionExists
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder

/** The template calls of the ERP backend (`project-poll-templates.md`), over [ErpClient]'s token handling. */
class ErpTemplateRemote(private val client: ErpClient) : TemplateRemote {
    private fun path(pid: String, templateId: String? = null) =
        "projects/$pid/poll-templates" + (templateId?.let { "/" + URLEncoder.encode(it, "UTF-8").replace("+", "%20") } ?: "")

    private fun envelope(text: String): JsonObject? = runCatching { PollJson.parseToJsonElement(text).jsonObject }.getOrNull()

    /** 403/404 mean "not yours to manage"; the server's own Vietnamese sentence wins when it has one. */
    private fun fail(r: ErpClient.RawResponse): Exception {
        val e = envelope(r.text)
        val text = e?.get("error")?.jsonPrimitive?.contentOrNull?.takeIf(ErpMessages::isUserSentence)
        return when {
            r.status == 400 -> TemplateSyncFailure(text ?: "Mẫu không hợp lệ.")
            r.status == 403 || r.status == 404 -> TemplateSyncFailure(text ?: "Bạn không có quyền quản lý mẫu của dự án này.")
            r.status == 409 && e?.get("err_key")?.jsonPrimitive?.contentOrNull == "TEMPLATE_VERSION_EXISTS" -> TemplateVersionExists()
            r.status == 413 -> TemplateSyncFailure("Mẫu quá lớn (tối đa 2 MB).")
            else -> ErpException(text ?: "Máy chủ ERP báo lỗi ${r.status}.")
        }
    }

    override suspend fun list(projectId: String): ProjectTemplatesInfo? {
        val r = client.rawAuthorized("GET", path(projectId), timeoutMs = 30_000)
        if (r.status == 404) return null
        if (r.status != 200) throw fail(r)
        val d = envelope(r.text)?.get("data") ?: throw ErpException("Máy chủ ERP trả về dữ liệu không đọc được.")
        return runCatching { PollJson.decodeFromJsonElement(ProjectTemplatesInfo.serializer(), d) }.getOrNull()
            ?: throw ErpException("Máy chủ ERP trả về dữ liệu không đọc được.")
    }

    override suspend fun add(projectId: String, content: JsonElement) {
        val body = JsonObject(mapOf("content" to content)).toString()
        val r = client.rawAuthorized("POST", path(projectId), json = body, timeoutMs = 30_000)
        if (r.status != 200) throw fail(r)
    }

    override suspend fun patch(projectId: String, templateId: String, version: String?, title: String?, shortName: String?, color: String?) {
        val o = buildMap<String, JsonElement> {
            version?.let { put("version", JsonPrimitive(it)) }; title?.let { put("title", JsonPrimitive(it)) }
            shortName?.let { put("short_name", JsonPrimitive(it)) }; color?.let { put("color", JsonPrimitive(it)) }
        }
        val r = client.rawAuthorized("PATCH", path(projectId, templateId), json = JsonObject(o).toString(), timeoutMs = 30_000)
        if (r.status != 200) throw fail(r)
    }

    override suspend fun remove(projectId: String, templateId: String): Boolean {
        val r = client.rawAuthorized("DELETE", path(projectId, templateId), timeoutMs = 30_000)
        if (r.status == 404) return false
        if (r.status !in 200..299) throw fail(r)
        return true
    }
}

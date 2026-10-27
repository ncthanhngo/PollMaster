package com.ncthanhngo.pollmaster.core.templateeditor

import com.ncthanhngo.pollmaster.core.storage.writeAtomically
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.util.UUID

/** A template being edited on this device (`template-schema.md` 11.1). Never synced. */
data class TemplateDraft(
    val draftId: String = UUID.randomUUID().toString(),
    /** Null for a new template. */
    val baseId: String?,
    val baseVersion: String?,
    /** Project the release goes to. */
    val projectId: String,
    val createdAt: String,
    val updatedAt: String,
    /** Version handed out for the release in progress; sending again after a lost reply repeats it. */
    val publishVersion: String? = null,
    val content: JsonElement,
) {
    /** The template's id (the base's, or the typed one of a new template). */
    val templateId: String get() = baseId ?: content.obj()?.get("id").str().orEmpty()

    val doc: TemplateDoc get() = TemplateDoc(content)

    fun withDoc(doc: TemplateDoc) = copy(content = doc.root)

    internal fun toJson(): JsonObject {
        fun opt(s: String?): JsonElement = s?.let(::JsonPrimitive) ?: JsonNull
        return JsonObject(mapOf(
            "draftId" to JsonPrimitive(draftId), "baseId" to opt(baseId), "baseVersion" to opt(baseVersion), "projectId" to JsonPrimitive(projectId),
            "createdAt" to JsonPrimitive(createdAt), "updatedAt" to JsonPrimitive(updatedAt), "publishVersion" to opt(publishVersion), "content" to content,
        ))
    }

    companion object {
        internal fun fromJson(v: JsonElement): TemplateDraft? {
            val o = v.obj() ?: return null
            val id = o["draftId"].str() ?: return null
            val pid = o["projectId"].str() ?: return null
            val content = o["content"]?.takeIf { it is JsonObject } ?: return null
            return TemplateDraft(id, o["baseId"].str(), o["baseVersion"].str(), pid, o["createdAt"].str().orEmpty(), o["updatedAt"].str().orEmpty(), o["publishVersion"].str(), content)
        }

        /** Editing [content] of `id@version` for release into [projectId]. */
        fun editing(id: String, version: String, content: JsonElement, projectId: String, now: String) =
            TemplateDraft(baseId = id, baseVersion = version, projectId = projectId, createdAt = now, updatedAt = now, content = content)

        /** An empty template: one empty section, a fresh code ([taken] = ids in the library on this device). */
        fun blank(projectId: String, taken: Set<String>, now: String, random: () -> String = TemplateIds::randomSuffix): TemplateDraft {
            fun s(v: String) = JsonPrimitive(v)
            val section = JsonObject(mapOf("id" to s("A"), "title" to s("Mục mới"), "items" to JsonArray(emptyList())))
            val content = JsonObject(mapOf(
                "schemaVersion" to JsonPrimitive(1), "id" to s(TemplateIds.templateId(null, "Mẫu mới", taken, random)), "version" to s("1.0"), "title" to s("Mẫu mới"),
                "header" to JsonArray(emptyList()), "sections" to JsonArray(listOf(section)),
                "signatures" to JsonArray(emptyList()),
            ))
            return TemplateDraft(baseId = null, baseVersion = null, projectId = projectId, createdAt = now, updatedAt = now, content = content)
        }

        /** A new template made from a copy of [source]: new code, version 1.0, "(bản sao)" after the title. */
        fun clone(source: JsonElement, projectId: String, taken: Set<String>, now: String, random: () -> String = TemplateIds::randomSuffix): TemplateDraft {
            val o = (source.obj() ?: JsonObject(emptyMap())).toMutableMap()
            val title = (o["title"].str() ?: "Mẫu") + " (bản sao)"
            o["id"] = JsonPrimitive(TemplateIds.templateId(o["shortName"].str(), title, taken, random))
            o["version"] = JsonPrimitive("1.0")
            o["title"] = JsonPrimitive(title)
            return TemplateDraft(baseId = null, baseVersion = null, projectId = projectId, createdAt = now, updatedAt = now, content = JsonObject(o))
        }
    }
}

/** `<root>/template-drafts/<draftId>.json`, written atomically. */
class TemplateDraftStore(root: File) {
    private val dir = File(root, "template-drafts")

    private fun file(id: String) = File(dir, id.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".json")

    private fun read(f: File): TemplateDraft? = runCatching { TemplateDraft.fromJson(TemplateDoc.parse(f.readText())) }.getOrNull()

    fun save(d: TemplateDraft) = writeAtomically(file(d.draftId), TemplateJsonWriter.write(d.toJson()).toByteArray(Charsets.UTF_8))

    fun load(draftId: String): TemplateDraft? = file(draftId).takeIf { it.isFile }?.let(::read)

    /** Newest edit first; unreadable files are skipped. */
    fun list(): List<TemplateDraft> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty().mapNotNull(::read)
            .sortedWith(compareByDescending<TemplateDraft> { it.updatedAt }.thenByDescending { it.draftId })

    fun delete(draftId: String) { file(draftId).delete() }

    /** The one draft this device keeps for a template id (null when none). */
    fun existing(templateId: String): TemplateDraft? = list().firstOrNull { it.templateId == templateId }
}

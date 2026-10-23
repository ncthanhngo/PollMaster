package com.ncthanhngo.pollmaster.core.editor

import com.ncthanhngo.pollmaster.core.format.nowIso
import com.ncthanhngo.pollmaster.core.format.todayIso
import com.ncthanhngo.pollmaster.core.model.Field
import com.ncthanhngo.pollmaster.core.model.FieldValue
import com.ncthanhngo.pollmaster.core.model.Record
import com.ncthanhngo.pollmaster.core.model.Template
import com.ncthanhngo.pollmaster.core.model.allFields
import com.ncthanhngo.pollmaster.core.model.findTable
import com.ncthanhngo.pollmaster.core.model.normalized
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID

const val SURVEYOR_FIELD_ID = "HD-surveyor"

object RecordFactory {

    fun create(template: Template, surveyorName: String?, now: String = nowIso(), recordCode: String? = null, projectId: String? = null): Record {
        val values = HashMap<String, FieldValue>()
        for (f in template.allFields()) {
            val v: JsonElement? = when {
                f.type == "sessionTime" -> sessionStart(now)
                f.id == SURVEYOR_FIELD_ID && !surveyorName.isNullOrBlank() -> JsonPrimitive(surveyorName)
                f.role == "recordCode" && !recordCode.isNullOrBlank() && f.default == null -> JsonPrimitive(recordCode)
                else -> defaultOf(f, now)
            }
            if (v != null) FieldValue(v = v).normalized()?.let { values[f.id] = it }
        }
        return Record(
            id = UUID.randomUUID().toString(),
            projectId = projectId,
            templateId = template.id,
            templateVersion = template.version,
            createdAt = now,
            updatedAt = now,
            values = values,
        )
    }

    /** Copies values (without media), resets status to draft and restarts the session time. */
    fun duplicate(template: Template, source: Record, now: String = nowIso()): Record {
        val mediaFields = template.allFields().filter { it.isMedia }.map { it.id }.toSet()
        val values = source.values.mapNotNull { (id, fv) ->
            val stripped = fv.copy(media = emptyList(), v = if (id in mediaFields) null else fv.v)
            val withSession = if (template.allFields().firstOrNull { it.id == id }?.type == "sessionTime") {
                stripped.copy(v = sessionStart(now))
            } else {
                stripped
            }
            withSession.normalized()?.let { id to it }
        }.toMap()
        val tables = source.tables.mapValues { (tableId, rows) ->
            val columns = template.findTable(tableId)?.columns.orEmpty().associateBy { it.id }
            rows.map { row ->
                row.copy(
                    values = row.values.mapNotNull { (cid, fv) ->
                        val isMedia = columns[cid]?.isMedia == true
                        fv.copy(media = emptyList(), v = if (isMedia) null else fv.v).normalized()?.let { cid to it }
                    }.toMap(),
                    stopwatchStart = null,
                )
            }
        }
        return source.copy(
            id = UUID.randomUUID().toString(),
            status = Record.STATUS_DRAFT,
            createdAt = now,
            updatedAt = now,
            completedAt = null,
            values = values,
            tables = tables,
            signatures = emptyMap(),
        )
    }

    fun sessionStart(now: String): JsonElement = JsonObject(mapOf("start" to JsonPrimitive(now), "end" to JsonNull))

    private fun defaultOf(f: Field, now: String): JsonElement? {
        val d = f.default ?: return null
        val text = (d as? JsonPrimitive)?.takeIf { it.isString }?.content
        return when (text) {
            "\$today" -> JsonPrimitive(todayIso())
            "\$now" -> JsonPrimitive(now)
            else -> d
        }
    }
}

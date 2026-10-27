package com.ncthanhngo.pollmaster.core

import com.ncthanhngo.pollmaster.core.model.PollJson
import com.ncthanhngo.pollmaster.core.model.Record
import com.ncthanhngo.pollmaster.core.model.Row
import com.ncthanhngo.pollmaster.core.model.Template
import com.ncthanhngo.pollmaster.core.storage.Backup
import com.ncthanhngo.pollmaster.core.storage.TemplateParser
import com.ncthanhngo.pollmaster.core.format.parseInstant
import com.ncthanhngo.pollmaster.core.project.Project
import com.ncthanhngo.pollmaster.core.sync.ChangeLabels
import com.ncthanhngo.pollmaster.core.sync.RecordAccess
import com.ncthanhngo.pollmaster.core.sync.RecordSyncState
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.ZoneOffset

/** Runs the phase-06 sections of `shared/sync-test-vectors.json` (labels, values, canEdit, hidden, syncState), as iOS does. */
class ChangeLabelsTest {
    private val vectors: JsonObject = PollJson.parseToJsonElement(File("../../shared/sync-test-vectors.json").readText()).jsonObject

    private fun cases(key: String) = vectors.getValue(key).jsonArray.map { it.jsonObject }
    private fun JsonObject.text(k: String) = getValue(k).jsonPrimitive.content
    private fun JsonObject.opt(k: String) = this[k]?.takeIf { it !is JsonNull }?.jsonPrimitive?.contentOrNull
    private fun template(file: String?): Template? = file?.let { TemplateParser.parse(File("../../shared/templates/$it").readText()) }

    @Test fun labelVectors() {
        val all = cases("labels")
        assertTrue(all.size >= 20)
        for (c in all) {
            val t = template(c.opt("template"))
            var r: Record? = t?.let { TestData.emptyRecord(it) }
            for ((tid, ids) in c.getValue("tableRows").jsonObject) {
                r = r?.copy(tables = r.tables + (tid to ids.jsonArray.map { Row(it.jsonPrimitive.content) }))
            }
            assertEquals(c.text("name"), c.text("expect"), ChangeLabels.label(c.text("path"), c.text("serverLabel"), t, r))
        }
    }

    @Test fun valueVectors() {
        val all = cases("values")
        assertTrue(all.size >= 20)
        for (c in all) {
            assertEquals(c.text("name"), c.text("expect"), ChangeLabels.valueText(c.text("path"), c.getValue("value") as JsonElement, template(c.opt("template"))))
        }
    }

    @Test fun canEditVectors() {
        for (c in cases("canEdit")) {
            assertEquals(c.text("name"), c.getValue("expect").jsonPrimitive.boolean,
                RecordAccess.canEdit(c.opt("createdBy"), c.opt("me"), c.getValue("canPost").jsonPrimitive.boolean, c.getValue("canManage").jsonPrimitive.boolean))
        }
    }

    @Test fun hiddenVectors() {
        for (c in cases("hidden")) {
            assertEquals(c.text("name"), c.getValue("expect").jsonPrimitive.boolean,
                RecordAccess.isHiddenLeftover(c.opt("createdBy"), c.opt("me"), c.getValue("dirty").jsonPrimitive.boolean, c.getValue("canManage").jsonPrimitive.boolean))
        }
    }

    @Test fun syncStateVectors() {
        for (c in cases("syncState")) {
            val s = RecordSyncState.of(c.getValue("dirty").jsonPrimitive.boolean, c.getValue("conflict").jsonPrimitive.boolean, c.opt("failure"), parseInstant(c.opt("syncedAt")))
            assertEquals(c.text("name"), c.text("expect") to c.text("text"), s.kind to s.text(ZoneOffset.ofHours(7)))
        }
    }

    @Test fun recordWithoutCreatorIsEditableByItsDeviceUser() {
        val r = TestData.emptyRecord()
        val p = Project(id = "p")
        assertTrue(RecordAccess.canEdit(r, "u1", p))
        assertFalse(RecordAccess.canEdit(r.copy(createdBy = "u2"), "u1", p))
        assertTrue(RecordAccess.canEdit(r.copy(createdBy = "u2"), "u1", p.copy(canManage = true)))
    }

    @Test fun conflictCopyNameKeepsTheBackupPrefixAndAddsTheTime() {
        assertEquals("P1_001-A_abcd1234_ban-may_20261027-160507.zip", Backup.conflictCopyName("001/A".replace("/", "-"), "abcd1234-0000", "P1", "20261027-160507"))
    }
}

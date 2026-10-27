package com.ncthanhngo.pollmaster.core

import com.ncthanhngo.pollmaster.core.model.PollJson
import com.ncthanhngo.pollmaster.core.model.Record
import com.ncthanhngo.pollmaster.core.sync.SyncPlanner
import com.ncthanhngo.pollmaster.core.sync.SyncRef
import com.ncthanhngo.pollmaster.core.sync.isSyncDirty
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Runs `shared/sync-test-vectors.json`, the same file the iOS tests run. */
class SyncPlannerTest {
    private val vectors: JsonObject = PollJson.parseToJsonElement(File("../../shared/sync-test-vectors.json").readText()).jsonObject

    private fun locals(a: JsonElement) = a.jsonArray.map {
        val o = it.jsonObject
        SyncPlanner.Local(
            o.getValue("id").jsonPrimitive.content, o["createdBy"]?.jsonPrimitive?.contentOrNull,
            o["serverRev"]?.takeIf { v -> v !is JsonNull }?.jsonPrimitive?.int, o.getValue("conflict").jsonPrimitive.boolean,
            o.getValue("dirty").jsonPrimitive.boolean,
        )
    }

    private fun refs(a: JsonElement) = a.jsonArray.map { SyncRef(it.jsonObject.getValue("id").jsonPrimitive.content, it.jsonObject.getValue("rev").jsonPrimitive.int) }

    private fun strings(a: JsonElement) = (a as JsonArray).map { it.jsonPrimitive.content }

    @Test fun planVectors() {
        val cases = vectors.getValue("plan").jsonArray
        assertTrue(cases.size >= 12)
        for (c in cases.map { it.jsonObject }) {
            val got = SyncPlanner.plan(
                locals(c.getValue("local")), refs(c.getValue("changed")), refs(c.getValue("ids")), strings(c.getValue("deleted")).toSet(),
                c.getValue("me").jsonPrimitive.content, c.getValue("canPost").jsonPrimitive.boolean, c.getValue("canManage").jsonPrimitive.boolean,
            ).mapValues { it.value.key }
            val want = c.getValue("expect").jsonObject.mapValues { it.value.jsonPrimitive.content }
            assertEquals(c.getValue("name").jsonPrimitive.content, want, got)
        }
    }

    @Test fun accountSwitchVectors() {
        for (c in vectors.getValue("accountSwitch").jsonArray.map { it.jsonObject }) {
            assertEquals(c.getValue("name").jsonPrimitive.content, strings(c.getValue("expect")).toSet(),
                SyncPlanner.staleAfterAccountSwitch(locals(c.getValue("local")), c.getValue("me").jsonPrimitive.content).toSet())
        }
    }

    @Test fun lostProjectVectors() {
        for (c in vectors.getValue("lostProject").jsonArray.map { it.jsonObject }) {
            assertEquals(c.getValue("name").jsonPrimitive.content, strings(c.getValue("expect")).toSet(), SyncPlanner.lostWithProject(locals(c.getValue("local"))).toSet())
        }
    }

    @Test fun sameContentVectors() {
        for (c in vectors.getValue("sameContent").jsonArray.map { it.jsonObject }) {
            assertEquals(c.getValue("name").jsonPrimitive.content, c.getValue("expect").jsonPrimitive.boolean,
                SyncPlanner.sameContent(c.getValue("a"), c.getValue("b")))
        }
    }

    @Test fun videoVectors() {
        for (c in vectors.getValue("isVideo").jsonArray.map { it.jsonObject }) {
            assertEquals(c.getValue("name").jsonPrimitive.content, c.getValue("expect").jsonPrimitive.boolean, SyncPlanner.isVideo(c.getValue("name").jsonPrimitive.content))
        }
    }

    @Test fun syncKeysRoundTripAndOldRecordsStillRead() {
        val r = TestData.emptyRecord().copy(createdBy = "u1", createdByName = "An", serverRev = 4, syncConflict = true)
        val back = PollJson.decodeFromString(Record.serializer(), PollJson.encodeToString(Record.serializer(), r))
        assertEquals(r, back)
        val old = PollJson.decodeFromString(Record.serializer(), """{"id":"x","templateId":"T"}""")
        assertNull(old.createdBy); assertNull(old.serverRev); assertFalse(old.syncConflict)
        val plain = PollJson.encodeToString(Record.serializer(), TestData.emptyRecord())
        assertFalse(plain.contains("serverRev") || plain.contains("syncConflict") || plain.contains("createdBy"))
    }

    @Test fun dirtyMeansNeverUploadedOrEditedSince() {
        var r = TestData.emptyRecord()
        assertTrue(r.isSyncDirty)
        r = r.copy(erpUploadedAt = r.updatedAt)
        assertFalse(r.isSyncDirty)
        r = r.copy(updatedAt = "2026-01-01T08:00:05+07:00")
        assertTrue(r.isSyncDirty)
    }
}

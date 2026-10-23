package com.ncthanhngo.pollmaster.core

import com.ncthanhngo.pollmaster.core.editor.EditorController
import com.ncthanhngo.pollmaster.core.editor.RecordFactory
import com.ncthanhngo.pollmaster.core.model.FieldValue
import com.ncthanhngo.pollmaster.core.model.PollJson
import com.ncthanhngo.pollmaster.core.model.Record
import com.ncthanhngo.pollmaster.core.storage.DebouncedSaver
import com.ncthanhngo.pollmaster.core.storage.InstallResult
import com.ncthanhngo.pollmaster.core.storage.RecordStore
import com.ncthanhngo.pollmaster.core.storage.StoredRecord
import com.ncthanhngo.pollmaster.core.storage.TemplateStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class StorageEditorTest {
    private val t = TestData.template

    @Test fun recordRoundTripsThroughJson() {
        val r = RecordFactory.create(t, "Nguyễn Văn A", "2026-03-01T09:00:00+07:00")
        val back = PollJson.decodeFromString(Record.serializer(), PollJson.encodeToString(Record.serializer(), r))
        assertEquals(r, back)
        assertEquals("Nguyễn Văn A", (back.values["HD-surveyor"]?.v as JsonPrimitive).content)
        assertEquals("KS-TDP-01", (back.values["HD-plan"]?.v as JsonPrimitive).content)
        assertNotNull(back.values["A-02"]) // sessionTime started
    }

    @Test fun recordStoreSavesAtomicallyAndListsNewestFirst() {
        val dir = TestData.tempDir()
        val store = RecordStore(dir)
        val a = RecordFactory.create(t, null, "2026-03-01T09:00:00+07:00").copy(updatedAt = "2026-03-01T09:00:00+07:00")
        val b = RecordFactory.create(t, null, "2026-03-02T09:00:00+07:00").copy(updatedAt = "2026-03-02T09:00:00+07:00")
        store.save(a)
        store.save(b)
        store.save(b.copy(status = "completed"))
        assertEquals(listOf(b.id, a.id), store.list().map { it.id })
        assertEquals("completed", store.load(b.id)?.status)
        assertFalse(File(store.dir(b.id), "record.json.tmp").exists())
        assertTrue(store.delete(a.id))
        assertNull(store.load(a.id))
    }

    @Test fun templateStoreInstallsAndAsksBeforeOverwrite() {
        val store = TemplateStore(TestData.tempDir())
        assertTrue(store.install(TestData.templateText, false) is InstallResult.Installed)
        assertTrue(store.install(TestData.templateText, false) is InstallResult.Exists)
        assertTrue(store.install(TestData.templateText, true) is InstallResult.Installed)
        assertTrue(store.install("{ nope", true) is InstallResult.Invalid)
        assertEquals(1, store.list().size)
        assertNotNull(store.get("BB-TDP-01", "1.0"))
    }

    @Test fun duplicateDropsMediaAndResetsStatus() {
        val base = RecordFactory.create(t, null, "2026-03-01T09:00:00+07:00")
        val media = PollJson.parseToJsonElement("""[{"file":"media/a.jpg"}]""")
        val src = base.copy(
            status = "completed", completedAt = "2026-03-01T10:00:00+07:00",
            values = base.values + ("P01-04" to FieldValue(v = media)) + ("HD-code" to TestData.text("X1")) +
                ("B-23" to FieldValue(v = JsonPrimitive("note"), media = listOf(com.ncthanhngo.pollmaster.core.model.Media("media/b.jpg")))),
        )
        val copy = RecordFactory.duplicate(t, src, "2026-04-01T09:00:00+07:00")
        assertTrue(copy.id != src.id)
        assertEquals("draft", copy.status)
        assertNull(copy.completedAt)
        assertNull(copy.values["P01-04"])
        assertTrue(copy.values["B-23"]!!.media.isEmpty())
        assertEquals("X1", (copy.values["HD-code"]!!.v as JsonPrimitive).content)
    }

    @Test fun autosaveIsDebouncedAndFlushWritesImmediately() = runTest {
        val written = mutableListOf<Record>()
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val saver = DebouncedSaver(scope, 500, write = { written += it })
        val c = EditorController(t, TestData.emptyRecord(), saver) { "2026-01-01T00:00:00+07:00" }
        c.updateValue("HD-code") { it.copy(v = JsonPrimitive("A")) }
        c.updateValue("HD-code") { it.copy(v = JsonPrimitive("AB")) }
        scope.testScheduler.advanceTimeBy(400)
        assertTrue(written.isEmpty())
        scope.testScheduler.advanceTimeBy(200)
        scope.testScheduler.runCurrent()
        assertEquals(1, written.size)
        assertEquals("AB", (written.single().values["HD-code"]!!.v as JsonPrimitive).content)
        c.updateValue("HD-code") { it.copy(v = JsonPrimitive("ABC")) }
        c.flush()
        assertEquals(2, written.size)
    }

    private fun controller(written: MutableList<Record> = mutableListOf()): EditorController {
        val scope = TestScope(StandardTestDispatcher())
        return EditorController(t, TestData.emptyRecord(), DebouncedSaver(scope, 500, write = { written += it })) { "2026-01-01T00:00:00+07:00" }
    }

    @Test fun stopwatchMarksFillColumnsInOrderAndUndo() {
        val c = controller()
        val rowId = c.addRow("F1")
        c.startStopwatch("F1", rowId)
        c.markStopwatch("F1", rowId, 12)
        c.markStopwatch("F1", rowId, 30)
        val row = c.record.value.tables.getValue("F1").single()
        assertEquals("2026-01-01T00:00:00+07:00", row.stopwatchStart)
        assertEquals(12L, (row.values["T1"]!!.v as JsonPrimitive).content.toLong())
        assertEquals(30L, (row.values["T2"]!!.v as JsonPrimitive).content.toLong())
        c.undoStopwatch("F1", rowId)
        assertNull(c.record.value.tables.getValue("F1").single().values["T2"])
        c.undoStopwatch("F1", rowId)
        c.undoStopwatch("F1", rowId)
        assertNull(c.record.value.tables.getValue("F1").single().stopwatchStart)
    }

    @Test fun tableRowOperations() {
        val c = controller()
        val id = c.addRow("E1")
        c.updateRowValue("E1", id, "mode") { it.copy(v = JsonPrimitive("O")) }
        val dup = c.duplicateRow("E1", id)!!
        assertEquals(2, c.record.value.tables.getValue("E1").size)
        assertEquals("O", (c.record.value.tables.getValue("E1")[1].values["mode"]!!.v as JsonPrimitive).content)
        c.deleteRow("E1", id)
        assertEquals(listOf(dup), c.record.value.tables.getValue("E1").map { it.id })
    }

    @Test fun completeIsBlockedUntilValidThenSetsEndAndReopens() {
        val c = controller()
        assertFalse(c.complete())
        assertEquals("draft", c.record.value.status)
        // fill every required field with a plausible value through the controller
        c.template.header.plus(c.template.sections.flatMap { it.items.filterIsInstance<com.ncthanhngo.pollmaster.core.model.Field>() })
            .filter { it.required }.forEach { f ->
                c.updateValue(f.id) { fillFor(f, it) }
            }
        val remaining = c.issues()
        assertTrue("remaining: $remaining", remaining.isEmpty())
        assertTrue(c.complete())
        assertEquals("completed", c.record.value.status)
        assertNotNull(c.record.value.completedAt)
        c.reopen()
        assertEquals("draft", c.record.value.status)
        assertNull(c.record.value.completedAt)
    }

    private fun fillFor(f: com.ncthanhngo.pollmaster.core.model.Field, cur: FieldValue): FieldValue {
        val parts = f.parts.associate { it.id to JsonPrimitive(1) as kotlinx.serialization.json.JsonElement }
        val one: kotlinx.serialization.json.JsonElement = when (f.type) {
            "number", "integer", "rating", "compass", "elapsed" -> JsonPrimitive((f.min ?: 1.0).coerceAtLeast(1.0))
            "yesNo" -> JsonPrimitive(false)
            "singleChoice" -> JsonPrimitive(f.options.first().value)
            "multiChoice" -> kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive(f.options.first().value)))
            "sessionTime" -> RecordFactory.sessionStart("2026-01-01T08:00:00+07:00")
            "gps" -> PollJson.parseToJsonElement("""{"lat":1.0,"lng":2.0}""")
            "photo", "video" -> PollJson.parseToJsonElement("""[{"file":"media/a.jpg"},{"file":"media/b.jpg"},{"file":"media/c.jpg"},{"file":"media/d.jpg"}]""")
            else -> JsonPrimitive("x")
        }
        val v = if (f.samples != null) kotlinx.serialization.json.JsonArray(List(f.samples) { one }) else one
        return if (f.type == "composite") cur.copy(parts = parts) else cur.copy(v = v)
    }

    @Test fun corruptRecordIsReportedAsBrokenAndOthersStayListed() {
        val store = RecordStore(TestData.tempDir())
        val good = TestData.emptyRecord()
        store.save(good)
        val badDir = store.dir("bad-1").also { it.mkdirs() }
        java.io.File(badDir, RecordStore.RECORD_FILE).writeText("{ not json")
        val all = store.listAll()
        assertEquals(listOf(good), all.filterIsInstance<StoredRecord.Loaded>().map { it.record })
        val broken = all.filterIsInstance<StoredRecord.Broken>().single()
        assertEquals("bad-1", broken.id)
        assertTrue(broken.error.isNotBlank())
        assertEquals(listOf(good), store.list())
    }
}

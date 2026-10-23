package com.ncthanhngo.pollmaster.core

import com.ncthanhngo.pollmaster.core.editor.EditorController
import com.ncthanhngo.pollmaster.core.editor.LookupFill
import com.ncthanhngo.pollmaster.core.editor.SectionTools
import com.ncthanhngo.pollmaster.core.export.Cell
import com.ncthanhngo.pollmaster.core.export.CsvWriter
import com.ncthanhngo.pollmaster.core.export.ExportBuilder
import com.ncthanhngo.pollmaster.core.export.ExportJson
import com.ncthanhngo.pollmaster.core.format.ValueFormatter
import com.ncthanhngo.pollmaster.core.model.Field
import com.ncthanhngo.pollmaster.core.model.FieldValue
import com.ncthanhngo.pollmaster.core.model.Media
import com.ncthanhngo.pollmaster.core.model.PollJson
import com.ncthanhngo.pollmaster.core.model.Record
import com.ncthanhngo.pollmaster.core.model.Row
import com.ncthanhngo.pollmaster.core.model.pairedTable
import com.ncthanhngo.pollmaster.core.model.tables
import com.ncthanhngo.pollmaster.core.sites.Site
import com.ncthanhngo.pollmaster.core.sites.SitesCsv
import com.ncthanhngo.pollmaster.core.sites.SitesCsvException
import com.ncthanhngo.pollmaster.core.sites.SitesStore
import com.ncthanhngo.pollmaster.core.sites.distanceMeters
import com.ncthanhngo.pollmaster.core.storage.Backup
import com.ncthanhngo.pollmaster.core.storage.DebouncedSaver
import com.ncthanhngo.pollmaster.core.storage.TemplateStore
import com.ncthanhngo.pollmaster.core.storage.compareVersions
import com.ncthanhngo.pollmaster.core.storage.newestVersions
import com.ncthanhngo.pollmaster.core.validation.WarningChecker
import com.ncthanhngo.pollmaster.core.validation.WarningKind
import com.ncthanhngo.pollmaster.core.validation.reviewNotes
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

@OptIn(ExperimentalCoroutinesApi::class)
class FieldWorkTest {
    private val t = TestData.template
    private var now = "2026-01-01T09:00:00+07:00"

    private fun controller(record: Record = TestData.emptyRecord(), saved: MutableList<Record> = mutableListOf()) =
        EditorController(t, record, DebouncedSaver(TestScope(StandardTestDispatcher()), 500, write = { saved += it })) { now }

    private fun num(v: Double) = FieldValue(v = JsonPrimitive(v))
    private fun gps(lat: Double, lng: Double, acc: Double? = null) = FieldValue(
        v = JsonObject(buildMap {
            put("lat", JsonPrimitive(lat)); put("lng", JsonPrimitive(lng)); acc?.let { put("accuracy", JsonPrimitive(it)) }
        }),
    )

    // ---- template 1.1 ----

    @Test fun template11DecodesFieldWorkKeys() {
        assertEquals("1.1", t.version)
        val a01 = t.header.plus(t.sections.flatMap { it.items.filterIsInstance<Field>() }).first { it.id == "A-01" }
        assertEquals(mapOf("A-03" to "address"), a01.lookup?.fill)
        assertEquals("A-04", a01.lookup?.gps)
        assertEquals("P08", t.sections.flatMap { it.items.filterIsInstance<Field>() }.first { it.id == "A-07" }.photoRef)
        assertEquals("F1", t.pairedTable("E1")?.id)
        assertEquals("E1", t.pairedTable("F1")?.id)
        assertNull(t.pairedTable("E2")) // fixed table
    }

    @Test fun versionsCompareNumericallyAndNewestWins() {
        assertTrue(compareVersions("1.10", "1.9") > 0)
        assertEquals(0, compareVersions("1.0", "1.0.0"))
        val store = TemplateStore(TestData.tempDir())
        store.install(TestData.templateText, false)
        store.install(TestData.templateText.replace("\"version\": \"1.1\"", "\"version\": \"1.0\""), false)
        assertEquals(listOf("1.0", "1.1"), store.list().map { it.version })
        assertEquals(listOf("1.1"), newestVersions(store.list()).map { it.version })
    }

    // ---- warnings ----

    @Test fun rangeWarningsCoverFieldsSamplesAndParts() {
        val checker = WarningChecker(t)
        val r = TestData.emptyRecord().copy(
            values = mapOf(
                "B-02" to num(3000.0), // warn 1000..2500
                "B-03" to num(1500.0),
                "G-22" to FieldValue(v = JsonArray(listOf(JsonPrimitive(20), JsonPrimitive(60), JsonPrimitive(25)))), // 15..45
            ),
        )
        val w = checker.check(r)
        val b02 = w.single { it.targetId == "B-02" }
        assertEquals(WarningKind.RANGE, b02.kind)
        assertEquals("Ngoài khoảng thường gặp 1000–2500 mm", b02.message)
        assertTrue(w.none { it.targetId == "B-03" })
        assertEquals(1, w.count { it.targetId == "G-22" })
    }

    @Test fun kddValuesAndEmptyValuesGiveNoWarning() {
        val r = TestData.emptyRecord().copy(
            values = mapOf("B-02" to FieldValue(v = JsonPrimitive(9999), flag = FieldValue.FLAG_NOT_MEASURABLE, reason = "x")),
        )
        assertTrue(WarningChecker(t).check(r).isEmpty())
    }

    @Test fun gpsAccuracyAndSiteDistanceWarnButDoNotBlock() {
        val site = Site("TD-01", "1 Lê Lợi", 10.0, 106.0)
        val checker = WarningChecker(t, listOf(site))
        val far = TestData.emptyRecord().copy(values = mapOf("A-01" to TestData.text("td-01"), "A-04" to gps(10.01, 106.0, 40.0)))
        val w = checker.check(far)
        assertEquals("Độ chính xác GPS kém (±40 m)", w.single { it.kind == WarningKind.GPS_ACCURACY }.message)
        val dist = w.single { it.kind == WarningKind.SITE_DISTANCE }
        assertTrue(dist.message, dist.message.startsWith("Cách điểm kế hoạch 11") && dist.message.endsWith(" m"))
        val near = far.copy(values = far.values + ("A-04" to gps(10.0001, 106.0, 5.0)))
        assertTrue(checker.check(near).isEmpty())
        assertEquals(1112.0, distanceMeters(10.0, 106.0, 10.01, 106.0), 5.0)
    }

    @Test fun warningsAreNotCountedInIssuesOrProgress() {
        val c = controller()
        val before = c.progress()
        c.updateValue("B-02") { it.copy(v = JsonPrimitive(99999)) }
        assertTrue(c.issues().none { it.targetId == "B-02" })
        assertEquals(before.total, c.progress().total)
        assertEquals(before.filled + 1, c.progress().filled)
    }

    // ---- sites & lookup ----

    @Test fun sitesCsvHandlesBomQuotesAndExtraColumns() {
        val csv = "﻿code,address,lat,lng,note\r\nTD-01,\"12 Lê Lợi, Q1\",10.5,106.25,x\r\nTD-02,\"Say \"\"hi\"\"\",,\r\n,skipped,1,2\r\n"
        val sites = SitesCsv.parse(csv)
        assertEquals(listOf("TD-01", "TD-02"), sites.map { it.code })
        assertEquals("12 Lê Lợi, Q1", sites[0].address)
        assertEquals(10.5, sites[0].lat!!, 0.0)
        assertEquals("Say \"hi\"", sites[1].address)
        assertFalse(sites[1].hasCoordinates)
    }

    @Test fun sitesCsvWithoutCodeColumnIsRejectedAndStoreRoundTrips() {
        try {
            SitesCsv.parse("id,address\n1,2")
            throw AssertionError("expected failure")
        } catch (e: SitesCsvException) {
            assertTrue(e.message!!.contains("code"))
        }
        val store = SitesStore(TestData.tempDir())
        assertTrue(store.load().isEmpty())
        val list = listOf(Site("A", "addr", 1.0, 2.0), Site("B"))
        store.save(list)
        assertEquals(list, store.load())
        store.clear()
        assertTrue(store.load().isEmpty())
    }

    @Test fun lookupFillsEmptyTargetsAndAsksBeforeOverwriting() {
        val a01 = t.header.plus(t.sections.flatMap { it.items.filterIsInstance<Field>() }).first { it.id == "A-01" }
        val site = Site("TD-01", "1 Lê Lợi", 10.0, 106.0)
        val empty = LookupFill.plan(a01, site, TestData.emptyRecord(), now)
        assertEquals(setOf("A-03"), empty.fresh.keys)
        assertTrue(empty.overwrites.isEmpty())

        val c = controller(TestData.emptyRecord().copy(values = mapOf("A-03" to TestData.text("cũ"))))
        c.applyLookup(a01, site, overwrite = false)
        assertEquals("cũ", (c.record.value.values.getValue("A-03").v as JsonPrimitive).content)
        assertEquals("TD-01", (c.record.value.values.getValue("A-01").v as JsonPrimitive).content)
        assertNull(c.record.value.values["A-04"]) // planned coordinates never count as a GPS reading
        assertTrue("A-03" in c.lookupPlan(a01, site).overwrites.keys)
        c.applyLookup(a01, site, overwrite = true)
        assertEquals("1 Lê Lợi", (c.record.value.values.getValue("A-03").v as JsonPrimitive).content)
    }

    // ---- tables ----

    @Test fun carryOverCopiesPreviousRowValuesOnlyForMarkedColumns() {
        val c = controller()
        val first = c.addRow("E1")
        c.updateRowValue("E1", first, "kb") { it.copy(v = JsonPrimitive("S03")) }
        c.updateRowValue("E1", first, "attempts") { it.copy(v = JsonPrimitive(2)) }
        val second = c.addRow("E1")
        val row = c.record.value.tables.getValue("E1").first { it.id == second }
        assertEquals("S03", (row.values.getValue("kb").v as JsonPrimitive).content)
        assertNull(row.values["attempts"])
    }

    @Test fun pairedTablesKeepRowIdsInSyncOnAddDuplicateDelete() {
        val c = controller()
        fun ids(table: String) = c.record.value.tables[table].orEmpty().map { it.id }
        val a = c.addRow("E1")
        val b = c.addRow("F1") // adding from the partner side also creates both
        assertEquals(listOf(a, b), ids("E1"))
        assertEquals(ids("E1"), ids("F1"))
        val dup = c.duplicateRow("E1", a)!!
        assertEquals(listOf(a, dup, b), ids("E1"))
        assertEquals(ids("E1"), ids("F1"))
        c.updateRowValue("F1", a, "batteries") { it.copy(v = JsonPrimitive(2)) }
        val dup2 = c.duplicateRow("F1", a)!!
        assertEquals(ids("E1"), ids("F1"))
        assertEquals(2.0, ((c.record.value.tables.getValue("F1")[1].values.getValue("batteries").v) as JsonPrimitive).content.toDouble(), 0.0)
        c.deleteRow("F1", a)
        assertEquals(listOf(dup2, dup, b), ids("E1"))
        assertEquals(ids("E1"), ids("F1"))
    }

    @Test fun unpairedTablesAreIndependent() {
        val c = controller()
        c.addRow("H1")
        assertTrue(c.record.value.tables["E1"].isNullOrEmpty())
    }

    // ---- review / returned / position / backup ----

    private fun completedController(): EditorController {
        val c = controller()
        // Everything required is irrelevant here: force the status the way a finished record looks.
        return EditorController(
            t, c.record.value.copy(status = Record.STATUS_COMPLETED, completedAt = now),
            DebouncedSaver(TestScope(StandardTestDispatcher()), 500, write = {}), { now },
        )
    }

    @Test fun reviewNotesAreStoredListedAndExported() {
        val c = completedController()
        c.updateValue("B-02") { it.copy(v = JsonPrimitive(1200)) }
        c.setReview("B-02", "Đo lại chiều cao")
        assertEquals("Đo lại chiều cao", c.record.value.values.getValue("B-02").review)
        assertEquals(listOf("B-02" to "Đo lại chiều cao"), reviewNotes(t, c.record.value).map { it.targetId to it.text })

        val json = ExportJson.build(t, c.record.value).getValue("fields").jsonObject.getValue("B-02").jsonObject
        assertEquals("Đo lại chiều cao", (json.getValue("review") as JsonPrimitive).content)

        val sheet = ExportBuilder(t, c.record.value).infoSheet()
        assertEquals("Kiểm tra", sheet.headers.last())
        val row = sheet.rows.first { (it[1] as Cell.Text).value == "B-02" }
        assertEquals("Đo lại chiều cao", (row.last() as Cell.Text).value)
        val csv = ByteArrayOutputStream().also { CsvWriter.write(sheet, it) }.toString(Charsets.UTF_8)
        assertTrue(csv.lines().first().endsWith("Ghi chú,Kiểm tra"))
        assertTrue(csv.contains("Đo lại chiều cao"))

        c.setReview("B-02", "  ")
        assertTrue(reviewNotes(t, c.record.value).isEmpty())
    }

    @Test fun returnForFixThenCompleteClearsReturned() {
        val c = completedController()
        assertFalse(c.record.value.returned)
        c.returnForFix()
        assertEquals(Record.STATUS_DRAFT, c.record.value.status)
        assertNull(c.record.value.completedAt)
        assertTrue(c.record.value.returned)
        val back = PollJson.decodeFromString(Record.serializer(), PollJson.encodeToString(Record.serializer(), c.record.value))
        assertTrue(back.returned)
        assertFalse(PollJson.encodeToString(Record.serializer(), TestData.emptyRecord()).contains("returned"))
    }

    @Test fun lastPositionAndBackupDoNotBumpUpdatedAt() {
        val saved = mutableListOf<Record>()
        val c = controller(saved = saved)
        c.setPosition("B", "B-02")
        assertEquals("B-02", c.record.value.lastPosition?.field)
        assertEquals(TestData.emptyRecord().updatedAt, c.record.value.updatedAt)
        c.markBackedUp("2026-01-01T09:30:00+07:00")
        c.flush()
        assertEquals("2026-01-01T09:30:00+07:00", saved.last().backupAt)
        assertEquals("B", saved.last().lastPosition?.section)
        val back = PollJson.decodeFromString(Record.serializer(), PollJson.encodeToString(Record.serializer(), saved.last()))
        assertEquals(saved.last(), back)
    }

    @Test fun needsBackupWhenNeverBackedUpOrChangedSince() {
        val r = TestData.emptyRecord()
        assertTrue(Backup.needsBackup(r))
        assertFalse(Backup.needsBackup(r.copy(backupAt = "2026-01-01T08:00:00+07:00")))
        assertFalse(Backup.needsBackup(r.copy(backupAt = "2026-01-01T09:00:00+07:00")))
        assertTrue(Backup.needsBackup(r.copy(backupAt = "2025-12-31T23:00:00+07:00")))
        assertTrue(Backup.needsBackup(r.copy(updatedAt = "2026-01-01T09:00:01+07:00", backupAt = "2026-01-01T09:00:00+07:00")))
        // same instant written with another offset
        assertFalse(Backup.needsBackup(r.copy(backupAt = "2026-01-01T01:30:00+00:00")))
    }

    @Test fun backupFileNameIsStablePerRecord() {
        assertEquals("HD-01-A_abcdef12_backup.zip", Backup.fileName("HD 01/A", "abcdef12-0000"))
        assertEquals("abcdef12-0000_backup.zip", Backup.fileName(null, "abcdef12-0000"))
    }

    // ---- section tools ----

    @Test fun markEmptyNotMeasurableOnlyTouchesEmptyFlaggableFields() {
        val c = controller()
        c.updateValue("B-02") { it.copy(v = JsonPrimitive(1200)) }
        val flaggable = c.emptyFlaggableCount("B")
        assertTrue(flaggable > 0)
        assertEquals(flaggable, c.markEmptyNotMeasurable("B", "Tủ đang khóa"))
        val v = c.record.value.values
        assertEquals(FieldValue.FLAG_NOT_MEASURABLE, v.getValue("B-03").flag)
        assertEquals("Tủ đang khóa", v.getValue("B-03").reason)
        assertNull(v.getValue("B-02").flag)
        assertEquals(0, c.emptyFlaggableCount("B"))
        // multiChoice does not offer flags: untouched
        assertNull(v["B-12"])
        assertEquals(0, c.markEmptyNotMeasurable("B", "again"))
    }

    @Test fun markNotMeasurableNeedsAReason() {
        val c = controller()
        assertEquals(c.emptyFlaggableCount("B"), c.markEmptyNotMeasurable("B", "  ").let { c.emptyFlaggableCount("B") })
        assertTrue(c.record.value.values.isEmpty())
    }

    @Test fun copySectionBringsValuesAndTablesButNoMediaAndKeepsTargetData() {
        val source = TestData.emptyRecord().copy(
            id = "src",
            values = mapOf(
                "B-02" to num(1500.0),
                "B-03" to FieldValue(v = JsonPrimitive(900), review = "kiểm tra", media = listOf(Media("media/x.jpg"))),
                "A-01" to TestData.text("khác mục"),
            ),
            tables = mapOf(
                "E1" to listOf(Row("r1", mapOf("kb" to TestData.text("S02"), "video" to FieldValue(v = PollJson.parseToJsonElement("""[{"file":"media/v.mp4"}]"""))))),
            ),
        )
        val target = TestData.emptyRecord().copy(values = mapOf("B-02" to num(1.0), "B-04" to num(5.0), "A-01" to TestData.text("giữ")))
        assertEquals(1, SectionTools.copyConflicts(t, target, source, "B")) // only B-02 has data on both sides
        val copied = SectionTools.copySection(t, target, source, "B")
        assertEquals(1500.0, (copied.values.getValue("B-02").v as JsonPrimitive).content.toDouble(), 0.0)
        assertNull(copied.values.getValue("B-03").review)
        assertTrue(copied.values.getValue("B-03").media.isEmpty())
        assertEquals(5.0, (copied.values.getValue("B-04").v as JsonPrimitive).content.toDouble(), 0.0) // source empty: kept
        assertEquals("giữ", (copied.values.getValue("A-01").v as JsonPrimitive).content) // other section untouched
        val sectionE = SectionTools.copySection(t, target, source, "E")
        val row = sectionE.tables.getValue("E1").single()
        assertEquals("r1", row.id)
        assertNull(row.values["video"])
        assertEquals("S02", (row.values.getValue("kb").v as JsonPrimitive).content)
        assertEquals(listOf("r1"), sectionE.tables.getValue("F1").map { it.id }) // partner gets matching (empty) rows
    }

    // ---- audio ----

    @Test fun audioDisplaysAndExportsAsFileNames() {
        val audio = t.tables().first { it.id == "H1" }.columns.first { it.id == "audio" }
        assertEquals("audio", audio.type)
        assertTrue(audio.isMedia)
        val fv = FieldValue(v = PollJson.parseToJsonElement("""[{"file":"media/HD_H1-audio_01.m4a"},{"file":"media/HD_H1-audio_02.m4a"}]"""))
        assertEquals("HD_H1-audio_01.m4a, HD_H1-audio_02.m4a", ValueFormatter().display(audio, fv))
        val r = TestData.emptyRecord().copy(
            tables = mapOf("H1" to listOf(Row("p1", mapOf("audio" to fv)))),
        )
        val row = ExportJson.build(t, r).getValue("tables").jsonObject.getValue("H1").jsonArray.single().jsonObject
        assertEquals(2, row.getValue("audio").jsonArray.size)
    }

    @Test fun sectionProgressIsNeutralCountPerSection() {
        val c = controller()
        val before = c.sectionProgress().getValue("B")
        assertTrue(before.total > 0)
        assertEquals(0, before.filled)
        c.updateValue("B-02") { it.copy(v = JsonPrimitive(1200)) }
        assertEquals(1, c.sectionProgress().getValue("B").filled)
        assertEquals(c.progress().total, c.sectionProgress().values.sumOf { it.total })
    }
}

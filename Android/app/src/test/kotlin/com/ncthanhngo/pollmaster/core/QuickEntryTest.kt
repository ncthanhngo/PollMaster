package com.ncthanhngo.pollmaster.core

import com.ncthanhngo.pollmaster.core.editor.FieldLayoutRules
import com.ncthanhngo.pollmaster.core.editor.MissingFields
import com.ncthanhngo.pollmaster.core.editor.MissingStep
import com.ncthanhngo.pollmaster.core.editor.OutlineStats
import com.ncthanhngo.pollmaster.core.editor.RowCount
import com.ncthanhngo.pollmaster.core.editor.SectionStats
import com.ncthanhngo.pollmaster.core.validation.Progress
import com.ncthanhngo.pollmaster.core.editor.RecordCodes
import com.ncthanhngo.pollmaster.core.editor.RecordFactory
import com.ncthanhngo.pollmaster.core.editor.RoundEntryRules
import com.ncthanhngo.pollmaster.core.editor.Stopwatch
import com.ncthanhngo.pollmaster.core.model.ChipsRange
import com.ncthanhngo.pollmaster.core.model.Field
import com.ncthanhngo.pollmaster.core.model.FieldValue
import com.ncthanhngo.pollmaster.core.model.HeadingItem
import com.ncthanhngo.pollmaster.core.model.Media
import com.ncthanhngo.pollmaster.core.model.Part
import com.ncthanhngo.pollmaster.core.model.Record
import com.ncthanhngo.pollmaster.core.model.Row
import com.ncthanhngo.pollmaster.core.model.RoundEntry
import com.ncthanhngo.pollmaster.core.model.asText
import com.ncthanhngo.pollmaster.core.model.findField
import com.ncthanhngo.pollmaster.core.model.findTable
import com.ncthanhngo.pollmaster.core.model.mediaToJson
import com.ncthanhngo.pollmaster.core.model.sample
import com.ncthanhngo.pollmaster.core.sites.LatLng
import com.ncthanhngo.pollmaster.core.sites.MapPins
import com.ncthanhngo.pollmaster.core.sites.PinStatus
import com.ncthanhngo.pollmaster.core.sites.Site
import com.ncthanhngo.pollmaster.core.sites.pinStatus
import com.ncthanhngo.pollmaster.core.validation.Validator
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickEntryTest {
    private val t = TestData.template
    private fun rec(vararg v: Pair<String, FieldValue>) = TestData.emptyRecord().copy(values = mapOf(*v))
    private fun num(n: Number) = FieldValue(v = JsonPrimitive(n))

    // ---- decoding ----

    @Test fun decodesNewKeys() {
        assertEquals("1.2", t.version)
        assertTrue(t.findField("HD-code")!!.short)
        assertTrue(t.findField("A-07")!!.short)
        val e1 = t.findTable("E1")!!
        val slot = e1.columns.first { it.id == "slotRow" }
        assertEquals(ChipsRange(1, "B-06.rows", 10), slot.chipsRange)
        assertEquals("integer", slot.type)
        assertTrue(e1.columns.first { it.id == "attempts" }.stepper)
        assertTrue(t.findTable("E2")!!.inline)
        val cues = t.findTable("F1")!!.stopwatch!!.cues
        assertEquals("G-33", cues.getValue("T1").field)
        assertEquals("G-32", cues.getValue("T6").field)
        val g2 = t.sections.flatMap { it.items }.filterIsInstance<HeadingItem>().first { it.id == "G2" }
        assertEquals(RoundEntry(3, "G-20"), g2.roundEntry)
    }

    // ---- chipsRange ----

    private val range = ChipsRange(1, "B-06.rows", 10)

    @Test fun chipsMaxFromPart() {
        val v = FieldValue(parts = mapOf("rows" to JsonPrimitive(4)))
        assertEquals(4, FieldLayoutRules.chipsMax(range) { if (it == "B-06") v else null })
    }

    @Test fun chipsMaxFallsBackWhenMissingOrInvalid() {
        assertEquals(10, FieldLayoutRules.chipsMax(range) { null })
        for (bad in listOf(JsonPrimitive(0), JsonPrimitive(-3), JsonPrimitive(2.5), JsonPrimitive("x"))) {
            val v = FieldValue(parts = mapOf("rows" to bad))
            assertEquals("bad $bad", 10, FieldLayoutRules.chipsMax(range) { v })
        }
        assertEquals(7, FieldLayoutRules.chipsMax(ChipsRange(1, null, 7)) { null })
        assertEquals(10, FieldLayoutRules.chipsMax(ChipsRange(5, "X", 10)) { num(3) })
    }

    @Test fun chipsMaxFromPlainField() {
        assertEquals(6, FieldLayoutRules.chipsMax(ChipsRange(1, "A-06", 10)) { num(6) })
    }

    // ---- record code ----

    @Test fun proposesNextCode() {
        assertEquals("002", RecordCodes.propose(listOf("001")))
        assertEquals("010", RecordCodes.propose(listOf("009", "003")))
        assertEquals("100", RecordCodes.propose(listOf("099")))
        assertEquals("13", RecordCodes.propose(listOf("KS-12", "x", null)))
        assertEquals("001", RecordCodes.propose(emptyList()))
        assertEquals("001", RecordCodes.propose(listOf("abc", " ", null)))
    }

    @Test fun proposalIsPerTemplateAndSetOnNewRecord() {
        val mine = rec("HD-code" to TestData.text("004"))
        val other = Record(id = "o", templateId = "OTHER", values = mapOf("HD-code" to TestData.text("900")))
        assertEquals("005", RecordCodes.propose(t, listOf(mine, other)))
        assertEquals("001", RecordCodes.propose(t, listOf(other)))
        val created = RecordFactory.create(t, "An", recordCode = "005")
        assertEquals("005", created.values["HD-code"]?.v.asText())
        assertNull(RecordFactory.create(t, "An").values["HD-code"])
    }

    // ---- roundEntry ----

    @Test fun groupsSamplesFieldsUnderRoundHeading() {
        val items = t.sections.first { it.id == "G" }.items
        val groups = RoundEntryRules.groups(items)
        assertEquals(setOf("G2"), groups.keys)
        val ids = groups.getValue("G2").map { it.id }
        assertEquals("G-20", ids.first())
        assertTrue("G-34" in ids && "G-36" in ids && "G-24" in ids)
        assertTrue(groups.getValue("G2").all { it.samples == 3 })
        assertEquals("G2", RoundEntryRules.headingOf(items, "G-30")?.id)
        assertNull(RoundEntryRules.headingOf(items, "G-05"))
    }

    @Test fun groupStopsAtNextHeading() {
        val heading = { id: String, r: RoundEntry? -> HeadingItem(id, id, roundEntry = r) }
        val f = { id: String, s: Int? -> Field(id, id, "number", samples = s) }
        val items = listOf(heading("H1", RoundEntry(2)), f("a", 2), f("plain", null), heading("H2", null), f("b", 2))
        assertEquals(mapOf("H1" to listOf("a")), RoundEntryRules.groups(items).mapValues { (_, v) -> v.map { it.id } })
    }

    @Test fun autoTimeFillsOnlyEmptyTimeOfThatRound() {
        val entry = RoundEntry(3, "G-20")
        val filled = RoundEntryRules.timeFill(entry, 1, "G-22", JsonPrimitive(30), null, "10:15")
        assertEquals(JsonArray(listOf(JsonNull, JsonPrimitive("10:15"), JsonNull)), filled)
        val withTime = FieldValue(v = JsonArray(listOf(JsonNull, JsonPrimitive("09:00"), JsonNull)))
        assertNull(RoundEntryRules.timeFill(entry, 1, "G-22", JsonPrimitive(30), withTime, "10:15"))
        assertNotNull(RoundEntryRules.timeFill(entry, 0, "G-22", JsonPrimitive(30), withTime, "10:15"))
        assertNull(RoundEntryRules.timeFill(entry, 0, "G-20", JsonPrimitive("08:00"), null, "10:15"))
        assertNull(RoundEntryRules.timeFill(entry, 0, "G-22", null, null, "10:15"))
        assertNull(RoundEntryRules.timeFill(RoundEntry(3, null), 0, "G-22", JsonPrimitive(1), null, "10:15"))
        val second = RoundEntryRules.timeFill(entry, 0, "G-22", JsonPrimitive(1), withTime, "10:15")!!
        assertEquals("09:00", FieldValue(v = second).sample(1)?.asText())
    }

    @Test fun firstEmptySampleForCue() {
        val v = FieldValue(v = JsonArray(listOf(JsonPrimitive(1), JsonNull, JsonNull)))
        assertEquals(1, RoundEntryRules.firstEmptySample(v, 3))
        assertEquals(0, RoundEntryRules.firstEmptySample(null, 3))
        assertEquals(2, RoundEntryRules.firstEmptySample(FieldValue(v = JsonArray(List(3) { JsonPrimitive(it + 1) })), 3))
    }

    // ---- outline stats ----

    @Test fun outlineCountsRequiredRowsAndMedia() {
        val m = { n: String -> Media(file = "media/$n.jpg") }
        val record = rec(
            "P08" to FieldValue(v = mediaToJson(listOf(m("a"), m("b")))),
            "P09" to FieldValue(v = mediaToJson(listOf(m("c")))),
            "P01-04" to FieldValue(v = mediaToJson(List(4) { m("p$it") })),
            "V01" to FieldValue(v = mediaToJson(List(3) { m("v$it") })),
        ).copy(tables = mapOf("E1" to listOf(Row("1", mapOf("kb" to TestData.text("S01"))), Row("2"), Row("3", mapOf("kb" to TestData.text("S02"))))))
        val stats = OutlineStats.compute(t, record)
        val i = stats.getValue("I")
        assertEquals(4, i.mediaFilled)
        assertEquals(t.sections.first { it.id == "I" }.items.count { (it as? Field)?.type == "photo" || (it as? Field)?.type == "video" }, i.mediaTotal)
        assertFalse(i.missingPhoto)
        assertEquals(listOf("lượt"), stats.getValue("E").rows.map { it.noun })
        assertEquals(2, stats.getValue("E").rows.single().count)
        val a = stats.getValue("A")
        assertTrue(a.required.total > 0 && a.required.filled < a.required.total)
        assertEquals(0, stats.getValue("header").required.filled)
    }

    @Test fun outlineSummaryLine() {
        val full = SectionStats(Progress(3, 5), listOf(RowCount(3, "lượt")), 12, 15, false)
        assertEquals("3/5 bắt buộc · 3 lượt · ảnh/video 12/15", full.summary())
        assertEquals("", SectionStats(Progress(0, 0), emptyList(), 0, 0, false).summary())
        assertEquals("2 người", SectionStats(Progress(0, 0), listOf(RowCount(2, "người")), 0, 0, false).summary())
        assertTrue(OutlineStats.compute(t, TestData.emptyRecord()).getValue("E").summary().contains("0 lượt"))
    }

    @Test fun outlineFlagsMissingRequiredPhoto() {
        val withReq = t.copy(sections = t.sections.map { s ->
            if (s.id == "I") s.copy(items = s.items.map { if (it.id == "P05") (it as Field).copy(required = true) else it }) else s
        })
        assertTrue(OutlineStats.compute(withReq, TestData.emptyRecord()).getValue("I").missingPhoto)
        val rec = rec(
            "P05" to FieldValue(v = mediaToJson(listOf(Media("media/x.jpg")))),
            "P01-04" to FieldValue(v = mediaToJson(List(4) { Media("media/p$it.jpg") })),
            "V01" to FieldValue(v = mediaToJson(List(3) { Media("media/v$it.mp4") })),
        )
        assertFalse(OutlineStats.compute(withReq, rec).getValue("I").missingPhoto)
        val minCount = withReq.copy(sections = withReq.sections.map { s ->
            if (s.id == "I") s.copy(items = s.items.map { if (it.id == "P05") (it as Field).copy(minCount = 2) else it }) else s
        })
        assertTrue(OutlineStats.compute(minCount, rec).getValue("I").missingPhoto)
    }

    // ---- Điền lần lượt ordering ----

    @Test fun missingStepsFollowTemplateOrder() {
        val required = t.copy(sections = t.sections.map { s ->
            if (s.id == "E") s.copy(items = s.items.map { if (it.id == "E1") (it as com.ncthanhngo.pollmaster.core.model.TableItem).copy(required = true) else it }) else s
        })
        val issues = Validator(required).validate(TestData.emptyRecord())
        val steps = MissingFields.ordered(required, issues)
        assertTrue(steps.isNotEmpty())
        assertEquals("HD-code", steps.first().issue.targetId)
        assertEquals(steps.size, steps.map { it.key }.toSet().size)
        val ids = steps.map { it.issue.targetId }
        // The validator lists a section's fields before its tables; steps follow the template: header, A, B, D, then table E1.
        assertTrue(ids.indexOf("HD-code") < ids.indexOf("A-01"))
        assertTrue(ids.indexOf("B-01") < ids.indexOf("D-04"))
        assertTrue(ids.indexOf("E1") > ids.indexOf("D-04"))
        assertEquals(MissingStep.Kind.TABLE, steps.first { it.issue.targetId == "E1" }.kind)
        assertEquals(MissingStep.Kind.FIELD, steps.first().kind)
    }

    @Test fun nextSkipsSkippedSteps() {
        val steps = MissingFields.ordered(t, Validator(t).validate(TestData.emptyRecord()))
        assertEquals(steps[0], MissingFields.next(steps, emptySet()))
        assertEquals(steps[2], MissingFields.next(steps, setOf(steps[0].key, steps[1].key)))
        assertNull(MissingFields.next(steps, steps.map { it.key }.toSet()))
        assertNull(MissingFields.next(emptyList(), emptySet()))
    }

    // ---- cues ----

    @Test fun cueForMark() {
        val def = t.findTable("F1")!!.stopwatch!!
        assertEquals("G-33", Stopwatch.cue(def, "T1")?.field)
        assertEquals("G-32", Stopwatch.cue(def, "T6")?.field)
        assertNull(Stopwatch.cue(def, "T2"))
    }

    // ---- layout rules ----

    @Test fun inlineChoiceColumnOfE2() {
        assertEquals("entered", FieldLayoutRules.inlineChoiceColumn(t.findTable("E2")!!)?.id)
        assertNull(FieldLayoutRules.inlineChoiceColumn(t.findTable("E1")!!))
        val e2 = t.findTable("E2")!!
        assertNull(FieldLayoutRules.inlineChoiceColumn(e2.copy(inline = false)))
        val fiveOptions = Field("c", "c", "singleChoice", options = List(5) { com.ncthanhngo.pollmaster.core.model.Option("$it", "$it") })
        assertEquals("entered", FieldLayoutRules.inlineChoiceColumn(e2.copy(columns = listOf(fiveOptions) + e2.columns))?.id)
    }

    @Test fun compactOnlyForNumbersAndShortText() {
        assertTrue(FieldLayoutRules.isCompactable(Field("a", "a", "number")))
        assertTrue(FieldLayoutRules.isCompactable(Field("a", "a", "integer")))
        assertTrue(FieldLayoutRules.isCompactable(t.findField("A-07")!!.copy(photoRef = null)))
        assertFalse(FieldLayoutRules.isCompactable(t.findField("A-03")!!))
        assertFalse(FieldLayoutRules.isCompactable(Field("a", "a", "text")))
        assertFalse(FieldLayoutRules.isCompactable(Field("a", "a", "number", samples = 3)))
        assertFalse(FieldLayoutRules.isCompactable(t.findField("B-06")!!))
    }

    @Test fun usualRangePrefersWarnBoundsThenMinMax() {
        assertEquals("Thường 1000–2500 mm", FieldLayoutRules.usualRange(Field("a", "a", "number", unit = "mm", min = 0.0, max = 9000.0, warnMin = 1000.0, warnMax = 2500.0)))
        assertEquals("Thường 0–1.5", FieldLayoutRules.usualRange(Field("a", "a", "number", min = 0.0, max = 1.5)))
        assertNull(FieldLayoutRules.usualRange(Field("a", "a", "number", min = 0.0)))
        assertNull(FieldLayoutRules.usualRange(Field("a", "a", "text", min = 0.0, max = 5.0)))
    }

    @Test fun threeShortNumberPartsShareOneRow() {
        val d02 = t.findField("D-02")!!.parts
        assertEquals(3, FieldLayoutRules.partsColumns(d02, wide = false))
        val mixed = listOf(Part("a", "a", "text"), Part("b", "b", "number"), Part("c", "c", "number"))
        assertEquals(2, FieldLayoutRules.partsColumns(mixed, wide = false))
        assertEquals(3, FieldLayoutRules.partsColumns(mixed, wide = true))
        assertEquals(2, FieldLayoutRules.partsColumns(t.findField("B-10")!!.parts, wide = true))
    }

    @Test fun emptyTableMessageNamesTheButton() {
        val e1 = t.findTable("E1")!!
        assertEquals("Chưa có lượt nào — bấm Ghi lượt mới", FieldLayoutRules.emptyTableMessage(e1, pairedQuick = true))
        assertEquals("Chưa có dòng nào — bấm Thêm dòng", FieldLayoutRules.emptyTableMessage(e1.copy(quickEntry = false, rowLabel = "Lượt"), pairedQuick = false))
    }

    // ---- records map ----

    private fun gps(lat: Double, lng: Double) =
        FieldValue(v = kotlinx.serialization.json.JsonObject(mapOf("lat" to JsonPrimitive(lat), "lng" to JsonPrimitive(lng))))

    @Test fun mapPinsUseFirstGpsAndStatus() {
        val r = rec("A-04" to gps(10.5, 106.25))
        assertEquals(LatLng(10.5, 106.25), MapPins.position(t, r))
        assertNull(MapPins.position(t, TestData.emptyRecord()))
        assertEquals(PinStatus.DRAFT, r.pinStatus())
        assertEquals(PinStatus.RETURNED, r.copy(returned = true).pinStatus())
        assertEquals(PinStatus.COMPLETED, r.copy(status = "completed", returned = true).pinStatus())
    }

    @Test fun unvisitedSitesAreThoseWithoutRecord() {
        val sites = listOf(Site("S1", "a", 1.0, 2.0), Site("S2", "b", 3.0, 4.0), Site("S3", "c"))
        val visited = MapPins.visitedCodes(listOf(t to rec("A-01" to TestData.text("S1"))))
        assertEquals(setOf("S1"), visited)
        assertEquals(listOf("S2"), MapPins.unvisited(sites, visited).map { it.code })
    }
}

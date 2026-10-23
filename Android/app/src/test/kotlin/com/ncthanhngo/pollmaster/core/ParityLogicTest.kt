package com.ncthanhngo.pollmaster.core

import com.ncthanhngo.pollmaster.core.export.Cell
import com.ncthanhngo.pollmaster.core.export.CsvWriter
import com.ncthanhngo.pollmaster.core.export.DocBlock
import com.ncthanhngo.pollmaster.core.export.DocxWriter
import com.ncthanhngo.pollmaster.core.export.ExportBuilder
import com.ncthanhngo.pollmaster.core.export.ExportJson
import com.ncthanhngo.pollmaster.core.export.SheetData
import com.ncthanhngo.pollmaster.core.export.XlsxWriter
import com.ncthanhngo.pollmaster.core.format.ValueFormatter
import com.ncthanhngo.pollmaster.core.format.formatElapsed
import com.ncthanhngo.pollmaster.core.format.recordTitle
import com.ncthanhngo.pollmaster.core.model.FieldValue
import com.ncthanhngo.pollmaster.core.model.PollJson
import com.ncthanhngo.pollmaster.core.model.Record
import com.ncthanhngo.pollmaster.core.model.Row
import com.ncthanhngo.pollmaster.core.model.Template
import com.ncthanhngo.pollmaster.core.model.isVisible
import com.ncthanhngo.pollmaster.core.storage.TemplateException
import com.ncthanhngo.pollmaster.core.storage.TemplateParser
import com.ncthanhngo.pollmaster.core.validation.IssueKind
import com.ncthanhngo.pollmaster.core.validation.Validator
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/** Validation, display, export and import rules of the shared schema (section 6). */
class ParityLogicTest {
    private fun tpl(body: String, extra: String = ""): Template =
        TemplateParser.parse("""{"id":"T","version":"1","title":"Tiêu đề mẫu",$extra "header":[$body]}""")

    private fun rec(t: Template, vararg values: Pair<String, FieldValue>, tables: Map<String, List<Row>> = emptyMap()) =
        TestData.emptyRecord(t).copy(values = mapOf(*values), tables = tables)

    private fun num(x: Double) = FieldValue(v = JsonPrimitive(x))

    private fun display(t: Template, r: Record, id: String): String {
        val f = t.header.first { it.id == id }
        return ValueFormatter { r.values[it] }.display(f, r.values[id])
    }

    // ---- import ----

    private fun rejects(body: String, vararg sections: String, pathPart: String) {
        try {
            val s = if (sections.isEmpty()) "" else ""","sections":[${sections.joinToString(",")}]"""
            TemplateParser.parse("""{"id":"T","version":"1","title":"t","header":[$body]$s}""")
            fail("accepted: $body")
        } catch (e: TemplateException) {
            assertTrue("path '$pathPart' in: ${e.message}", e.message!!.contains(pathPart))
        }
    }

    private fun computed(formula: String) = """{"id":"c","label":"c","type":"computed","formula":${JsonPrimitive(formula)}}"""
    private val table = """{"id":"TB","title":"tb","kind":"table","columns":[{"id":"x","label":"x","type":"number"}]}"""
    private val tableSection = """{"id":"S","title":"s","items":[$table]}"""

    @Test fun importRejectsInvalidFormulas() {
        rejects(computed("{a} +"), pathPart = "header[0].formula")
        rejects(computed("foo(1)"), pathPart = "header[0].formula")
        rejects(computed("round(1)"), pathPart = "header[0].formula")
        rejects(computed("if(true, 1)"), pathPart = "header[0].formula")
        rejects(computed("{@x} + 1"), pathPart = "header[0].formula")
        rejects(computed("avg(NOPE, {@x})"), tableSection, pathPart = "header[0].formula")
        rejects(computed("avg(TB)"), tableSection, pathPart = "header[0].formula")
        rejects(computed("count(TB, 1, 2)"), tableSection, pathPart = "header[0].formula")
    }

    @Test fun importAcceptsValidFormulas() {
        TemplateParser.parse("""{"id":"T","version":"1","title":"t","header":[${computed("avg(TB, {@x}, {@x} > 1)")}],"sections":[$tableSection]}""")
    }

    @Test fun importRejectsPartWithoutLabelWithPath() {
        rejects("""{"id":"a","label":"a","type":"text","parts":[{"id":"p"}]}""", pathPart = "header[0].parts[0]")
        val t = tpl("""{"id":"a","label":"a","type":"text","parts":[{"id":"p","label":"P"}]}""")
        assertEquals("text", t.header[0].parts[0].type)
    }

    @Test fun importRejectsChoiceWithoutOptions() {
        rejects("""{"id":"a","label":"a","type":"singleChoice"}""", pathPart = "header[0].options")
    }

    // ---- validation ----

    @Test fun progressDoesNotCountItemsWithIssues() {
        val t = tpl("""{"id":"n","label":"n","type":"number","required":true}""")
        val v = Validator(t)
        val noReason = rec(t, "n" to FieldValue(flag = "notMeasurable"))
        assertEquals(0, v.requiredProgress(noReason).filled)
        val blankReason = rec(t, "n" to FieldValue(flag = "notMeasurable", reason = "   "))
        assertEquals(0, v.requiredProgress(blankReason).filled)
        val withReason = rec(t, "n" to FieldValue(flag = "notMeasurable", reason = "hỏng"))
        assertEquals(1, v.requiredProgress(withReason).filled)
    }

    @Test fun whitespaceValueIsEmpty() {
        val t = tpl("""{"id":"n","label":"n","type":"text","required":true}""")
        assertEquals(IssueKind.REQUIRED, Validator(t).validate(rec(t, "n" to TestData.text("  "))).single().kind)
    }

    @Test fun requiredSignatureFieldNeedsFile() {
        val t = tpl("""{"id":"s","label":"s","type":"signature","required":true}""")
        val v = Validator(t)
        assertEquals(IssueKind.REQUIRED, v.validate(rec(t)).single().kind)
        val signed = rec(t, "s" to FieldValue(v = PollJson.parseToJsonElement("""{"file":"media/sig_s.png"}""")))
        assertTrue(v.validate(signed).isEmpty())
        assertEquals(1, v.requiredProgress(signed).filled)
    }

    @Test fun containsOnScalarFallsBackToEquality() {
        val t = tpl(
            """{"id":"k","label":"k","type":"singleChoice","options":[{"value":"a","label":"A"}],
            "parts":[{"id":"p","label":"P","showWhen":{"field":"k","contains":"a"}}]}""",
        )
        val k = t.header[0]
        val part = k.parts[0]
        fun visible(v: FieldValue) = part.isVisible(null) { v }
        assertTrue(visible(TestData.text("a")))
        assertTrue(!visible(TestData.text("b")))
        val multi = PollJson.parseToJsonElement("""["x","a"]""")
        assertTrue(visible(FieldValue(v = multi)))
    }

    @Test fun fixedTableIgnoresStoredRowsWithUnknownIds() {
        val t = TemplateParser.parse(
            """{"id":"T","version":"1","title":"t","sections":[{"id":"S","title":"s","items":[
            {"id":"TB","title":"tb","kind":"table","required":true,"rows":[{"id":"r1"}],
             "columns":[{"id":"x","label":"x","type":"number","required":true}]}]}]}""",
        )
        val v = Validator(t)
        val stray = TestData.emptyRecord(t).copy(tables = mapOf("TB" to listOf(Row("ghost", mapOf("x" to num(1.0))))))
        val issue = v.validate(stray).single()
        assertEquals(IssueKind.MIN_ROWS, issue.kind)
        assertEquals("Cần tối thiểu 1 dòng (hiện có 0)", issue.message())
        val merged = TestData.emptyRecord(t).copy(tables = mapOf("TB" to listOf(Row("ghost", mapOf("x" to num(1.0))), Row("r1", mapOf("x" to num(2.0))))))
        assertTrue(v.validate(merged).isEmpty())
    }

    @Test fun issueMessagesMatchContract() {
        val t = tpl(
            """{"id":"n","label":"n","type":"number","min":1,"max":3,"required":true},
            {"id":"p","label":"p","type":"photo","minCount":2,"required":true},
            {"id":"r","label":"r","type":"text","required":true}""",
        )
        val v = Validator(t)
        fun msgs(r: Record) = v.validate(r).map { it.message() }
        assertTrue(msgs(rec(t)).contains("Bắt buộc nhập"))
        assertTrue(msgs(rec(t, "n" to num(0.0))).contains("Giá trị phải ≥ 1"))
        assertTrue(msgs(rec(t, "n" to num(9.0))).contains("Giá trị phải ≤ 3"))
        assertTrue(msgs(rec(t)).contains("Cần tối thiểu 2 tệp (hiện có 0)"))
        assertTrue(msgs(rec(t, "r" to FieldValue(flag = "notMeasurable"))).contains("Cần nhập lý do KĐĐ"))
    }

    // ---- display ----

    @Test fun displayFormats() {
        val t = tpl(
            """{"id":"o","label":"o","type":"singleChoice","options":[{"value":"x","label":"Khác","withText":true}]},
            {"id":"g","label":"g","type":"gps"},
            {"id":"n","label":"n","type":"number"},
            {"id":"d","label":"d","type":"number","decimals":2},
            {"id":"s","label":"s","type":"sessionTime"},
            {"id":"e","label":"e","type":"elapsed"},
            {"id":"sg","label":"sg","type":"signature"}""",
        )
        val gps = PollJson.parseToJsonElement("""{"lat":10.5,"lng":106.25,"accuracy":5,"capturedAt":"2026-01-01T00:00:00+07:00"}""")
        val gpsNoAcc = PollJson.parseToJsonElement("""{"lat":10.5,"lng":106.25}""")
        val r = rec(
            t,
            "o" to FieldValue(v = JsonPrimitive("x"), other = mapOf("x" to "xyz")),
            "g" to FieldValue(v = gps),
            "n" to num(0.1234567),
            "d" to num(2.345),
            "s" to FieldValue(v = PollJson.parseToJsonElement("""{"start":"2026-10-23T10:00:00+07:00","end":null}""")),
            "e" to num(3725.0),
            "sg" to FieldValue(v = PollJson.parseToJsonElement("""{"file":"media/sig_a.png"}""")),
        )
        assertEquals("Khác: xyz", display(t, r, "o"))
        assertEquals("10.500000, 106.250000 (±5 m)", display(t, r, "g"))
        assertEquals("10.500000, 106.250000", display(t, r.copy(values = r.values + ("g" to FieldValue(v = gpsNoAcc))), "g"))
        assertEquals("0.1234567", display(t, r, "n"))
        assertEquals("2.35", display(t, r, "d"))
        assertTrue(display(t, r, "s").endsWith(" – …"))
        assertEquals("1:02:05", display(t, r, "e"))
        assertEquals("59:59", formatElapsed(3599.0))
        assertEquals("sig_a.png", display(t, r, "sg"))
    }

    @Test fun recordTitleFallsBackToNewRecordTitleAndTrimsBothEnds() {
        val t = tpl(
            """{"id":"a","label":"a","type":"text"},{"id":"b","label":"b","type":"text"}""",
            extra = """"recordTitle":"{a} – {b}",""",
        )
        assertEquals("Phiếu mới · 01/01/2026 08:00", recordTitle(t, rec(t)))
        assertEquals("B1", recordTitle(t, rec(t, "b" to TestData.text("B1"))))
        assertEquals("A1", recordTitle(t, rec(t, "a" to TestData.text("A1"))))
    }

    // ---- export ----

    private fun roundingTemplate() = tpl(
        """{"id":"n","label":"n","type":"number"},
        {"id":"d","label":"d","type":"number","decimals":2},
        {"id":"c","label":"c","type":"computed","formula":"{n} / 3","decimals":2},
        {"id":"p","label":"p","type":"photo"}""",
    )

    @Test fun xlsxAndCsvUseRoundedDisplayValues() {
        val t = roundingTemplate()
        val r = rec(t, "n" to num(1.0), "d" to num(2.345))
        val sheet = ExportBuilder(t, r).infoSheet()
        fun cell(id: String) = sheet.rows.first { (it[1] as Cell.Text).value == id }[3]
        assertEquals(Cell.Num(2.35), cell("d"))
        assertEquals(Cell.Num(0.33), cell("c"))
        assertEquals(Cell.Num(1.0), cell("n"))
        val csv = ByteArrayOutputStream().also { CsvWriter.write(sheet, it) }.toByteArray().decodeToString(3)
        assertTrue(csv, csv.contains(",2.35,"))
        assertTrue(csv, csv.contains(",0.33,"))
        val xml = zip(ByteArrayOutputStream().also { XlsxWriter.write(listOf(sheet), it) }.toByteArray()).getValue("xl/worksheets/sheet1.xml")
        assertTrue(xml.contains("<v>2.35</v>") && xml.contains("<v>0.33</v>"))
    }

    @Test fun exportJsonEmptyValuesAreNullAndTimestampsRaw() {
        val t = roundingTemplate()
        val r = rec(t, "n" to FieldValue(v = JsonPrimitive("  "))).copy(createdAt = "2026-10-23T10:00+07:00")
        val o = ExportJson.build(t, r)
        val fields = o.getValue("fields").jsonObject
        assertEquals(JsonNull, fields.getValue("p").jsonObject.getValue("value"))
        assertEquals(JsonNull, fields.getValue("n").jsonObject.getValue("value"))
        assertEquals("2026-10-23T10:00+07:00", o.getValue("record").jsonObject.getValue("createdAt").let { (it as JsonPrimitive).content })
    }

    @Test fun sheetNamesAreSanitizedTruncatedAndDeduped() {
        val long = "a/b:c*d?[e]\\f" + "x".repeat(40)
        val out = ByteArrayOutputStream()
        XlsxWriter.write(listOf(SheetData(long, listOf("h"), emptyList()), SheetData(long, listOf("h"), emptyList())), out)
        val wb = zip(out.toByteArray()).getValue("xl/workbook.xml")
        val names = Regex("""<sheet name="([^"]*)"""").findAll(wb).map { it.groupValues[1] }.toList()
        assertEquals("a_b_c_d__e__f" + "x".repeat(18), names[0])
        assertEquals(31, names[0].length)
        assertEquals(names[0].take(27) + " (2)", names[1])
    }

    @Test fun repeatTablesGetIndexColumnFixedDoNot() {
        val t = TemplateParser.parse(
            """{"id":"T","version":"1","title":"t","sections":[{"id":"S","title":"s","items":[
            {"id":"R","title":"rep","kind":"table","rowLabel":"Lượt","columns":[{"id":"x","label":"x","type":"number"}]},
            {"id":"F","title":"fix","kind":"table","rows":[{"id":"f1","fixed":{"k":"v"}}],"columns":[{"id":"k","label":"k","type":"text","readonly":true}]}]}]}""",
        )
        val r = TestData.emptyRecord(t).copy(
            tables = mapOf("R" to listOf(Row("a", mapOf("x" to num(1.0))), Row("b", mapOf("x" to num(2.0))))),
        )
        val grids = ExportBuilder(t, r).document().blocks.filterIsInstance<DocBlock.Grid>().drop(1)
        assertEquals(listOf("#", "x"), grids[0].headers)
        assertEquals(listOf("Lượt 1", "1"), grids[0].rows[0])
        assertEquals(listOf("Lượt 2", "2"), grids[0].rows[1])
        assertEquals(1f, grids[0].weights.sum(), 1e-4f)
        assertEquals(listOf("k"), grids[1].headers)
        assertEquals(listOf("v"), grids[1].rows.single())
    }

    @Test fun docxFooterHasPageAndNumPagesFields() {
        val t = roundingTemplate()
        val out = ByteArrayOutputStream()
        DocxWriter { null }.write(ExportBuilder(t, rec(t)).document(), out)
        val footer = zip(out.toByteArray()).getValue("word/footer1.xml")
        assertTrue(footer.contains("Trang ") && footer.contains(" PAGE ") && footer.contains(" NUMPAGES "))
    }

    private fun zip(bytes: ByteArray): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) m[(z.nextEntry ?: break).name] = z.readBytes().decodeToString()
        }
        return m
    }
}

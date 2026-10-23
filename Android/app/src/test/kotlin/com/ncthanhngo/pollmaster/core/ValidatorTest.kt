package com.ncthanhngo.pollmaster.core

import com.ncthanhngo.pollmaster.core.model.Field
import com.ncthanhngo.pollmaster.core.model.FieldValue
import com.ncthanhngo.pollmaster.core.model.Media
import com.ncthanhngo.pollmaster.core.model.PollJson
import com.ncthanhngo.pollmaster.core.model.Record
import com.ncthanhngo.pollmaster.core.model.Row
import com.ncthanhngo.pollmaster.core.model.mediaToJson
import com.ncthanhngo.pollmaster.core.storage.TemplateParser
import com.ncthanhngo.pollmaster.core.validation.IssueKind
import com.ncthanhngo.pollmaster.core.validation.Validator
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ValidatorTest {
    private val t = TestData.template
    private val validator = Validator(t)

    private fun issues(r: Record) = validator.validate(r)
    private fun kinds(r: Record, id: String) = issues(r).filter { it.targetId == id }.map { it.kind }

    @Test fun requiredFieldEmptyIsReported() {
        assertTrue(kinds(TestData.emptyRecord(), "HD-code").contains(IssueKind.REQUIRED))
    }

    @Test fun filledRequiredFieldPasses() {
        val r = TestData.emptyRecord().copy(values = mapOf("HD-code" to TestData.text("X1")))
        assertFalse(kinds(r, "HD-code").contains(IssueKind.REQUIRED))
    }

    @Test fun notMeasurableNeedsReason() {
        val noReason = TestData.emptyRecord().copy(values = mapOf("HD-code" to FieldValue(flag = "notMeasurable")))
        assertEquals(listOf(IssueKind.REASON_MISSING), kinds(noReason, "HD-code"))
        val withReason = TestData.emptyRecord().copy(values = mapOf("HD-code" to FieldValue(flag = "notMeasurable", reason = "hỏng")))
        assertTrue(kinds(withReason, "HD-code").isEmpty())
    }

    @Test fun numberMinMax() {
        val tpl = TemplateParser.parse(
            """{"id":"T","version":"1","title":"t","header":[{"id":"n","label":"n","type":"number","min":0,"max":10}]}""",
        )
        val v = Validator(tpl)
        fun rec(x: Double) = TestData.emptyRecord(tpl).copy(values = mapOf("n" to FieldValue(v = JsonPrimitive(x))))
        assertEquals(IssueKind.BELOW_MIN, v.validate(rec(-1.0)).single().kind)
        assertEquals(IssueKind.ABOVE_MAX, v.validate(rec(11.0)).single().kind)
        assertTrue(v.validate(rec(5.0)).isEmpty())
    }

    @Test fun samplesRequireOneValue() {
        val g = PollJson.parseToJsonElement("[null,null,null]")
        val empty = TestData.emptyRecord().copy(values = mapOf("G-20" to FieldValue(v = g)))
        assertTrue(kinds(empty, "G-20").contains(IssueKind.REQUIRED))
        val one = TestData.emptyRecord().copy(values = mapOf("G-20" to FieldValue(v = PollJson.parseToJsonElement("[null,\"09:30\",null]"))))
        assertFalse(kinds(one, "G-20").contains(IssueKind.REQUIRED))
    }

    @Test fun minCountForPhotos() {
        val media = listOf(Media("media/a.jpg"), Media("media/b.jpg"))
        val r = TestData.emptyRecord().copy(values = mapOf("P01-04" to FieldValue(v = mediaToJson(media))))
        assertEquals(listOf(IssueKind.MIN_COUNT), kinds(r, "P01-04"))
        assertTrue(kinds(TestData.emptyRecord(), "P01-04").isEmpty()) // optional and untouched
    }

    @Test fun hiddenPartIsNotRequired() {
        val tpl = TemplateParser.parse(
            """{"id":"T","version":"1","title":"t","header":[{"id":"c","label":"c","type":"composite","required":true,
            "parts":[{"id":"a","label":"a","type":"yesNo"},{"id":"h","label":"h","type":"number","showWhen":{"field":"${'$'}self","equals":true}}]}]}""",
        )
        fun rec(a: Boolean, h: Double?) = TestData.emptyRecord(tpl).copy(
            values = mapOf(
                "c" to FieldValue(parts = buildMap {
                    put("a", JsonPrimitive(a))
                    if (h != null) put("h", JsonPrimitive(h))
                }),
            ),
        )
        // "self" refers to v (null for composite), so the part stays hidden and is not required
        assertTrue(Validator(tpl).validate(rec(false, null)).isEmpty())
        val tpl2 = TemplateParser.parse(
            """{"id":"T","version":"1","title":"t","header":[{"id":"y","label":"y","type":"yesNo","required":true,
            "parts":[{"id":"h","label":"h","type":"number","showWhen":{"field":"${'$'}self","equals":true}}]}]}""",
        )
        assertTrue(Validator(tpl2).validate(TestData.emptyRecord(tpl2).copy(values = mapOf("y" to FieldValue(v = JsonPrimitive(false))))).isEmpty())
    }

    @Test fun compositeNeedsAllVisibleParts() {
        val partial = TestData.emptyRecord().copy(
            values = mapOf("B-06" to FieldValue(parts = mapOf("rows" to JsonPrimitive(2)))),
        )
        assertTrue(kinds(partial, "B-06").contains(IssueKind.REQUIRED))
        val full = TestData.emptyRecord().copy(
            values = mapOf("B-06" to FieldValue(parts = mapOf("rows" to JsonPrimitive(2), "cols" to JsonPrimitive(3), "screen" to JsonPrimitive("trái")))),
        )
        assertFalse(kinds(full, "B-06").contains(IssueKind.REQUIRED))
    }

    @Test fun tableMinRows() {
        val tpl = TemplateParser.parse(
            """{"id":"T","version":"1","title":"t","sections":[{"id":"S","title":"s","items":[
            {"kind":"table","id":"TB","title":"tb","required":true,"minRows":2,"columns":[{"id":"c","label":"c","type":"text"}]}]}]}""",
        )
        val v = Validator(tpl)
        fun row(id: String, text: String?) = Row(id, if (text == null) emptyMap() else mapOf("c" to TestData.text(text)))
        val one = TestData.emptyRecord(tpl).copy(tables = mapOf("TB" to listOf(row("1", "a"), row("2", null))))
        assertEquals(IssueKind.MIN_ROWS, v.validate(one).single().kind)
        val two = TestData.emptyRecord(tpl).copy(tables = mapOf("TB" to listOf(row("1", "a"), row("2", "b"))))
        assertTrue(v.validate(two).isEmpty())
    }

    @Test fun requiredTableNeedsAtLeastOneRow() {
        val tpl = TemplateParser.parse(
            """{"id":"T","version":"1","title":"t","sections":[{"id":"S","title":"s","items":[
            {"kind":"table","id":"TB","title":"tb","required":true,"columns":[{"id":"c","label":"c","type":"text"}]}]}]}""",
        )
        val v = Validator(tpl)
        assertEquals(IssueKind.MIN_ROWS, v.validate(TestData.emptyRecord(tpl)).single().kind)
        val one = TestData.emptyRecord(tpl).copy(tables = mapOf("TB" to listOf(Row("1", mapOf("c" to TestData.text("a"))))))
        assertTrue(v.validate(one).isEmpty())
    }

    @Test fun progressCountsRequired() {
        val empty = validator.requiredProgress(TestData.emptyRecord())
        assertTrue(empty.total >= 10)
        assertEquals(0, empty.filled)
        val r = TestData.emptyRecord().copy(values = mapOf("HD-code" to TestData.text("X")))
        assertEquals(1, validator.requiredProgress(r).filled)
    }

    @Test fun requiredFieldsExistInTemplate() {
        assertTrue(t.header.filterIsInstance<Field>().any { it.required })
    }
}

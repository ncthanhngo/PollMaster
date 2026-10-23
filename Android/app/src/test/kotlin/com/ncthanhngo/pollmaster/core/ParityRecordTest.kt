package com.ncthanhngo.pollmaster.core

import com.ncthanhngo.pollmaster.core.editor.mediaFileName
import com.ncthanhngo.pollmaster.core.editor.signatureFileName
import com.ncthanhngo.pollmaster.core.format.nowIso
import com.ncthanhngo.pollmaster.core.format.toIso
import com.ncthanhngo.pollmaster.core.model.FieldValue
import com.ncthanhngo.pollmaster.core.model.PollJson
import com.ncthanhngo.pollmaster.core.model.Record
import com.ncthanhngo.pollmaster.core.model.Row
import com.ncthanhngo.pollmaster.core.model.SignatureValue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.OffsetDateTime

/** Record write/read contract (shared schema section 6.1) and file naming (6.4). */
class ParityRecordTest {
    private fun encode(r: Record): JsonObject =
        PollJson.parseToJsonElement(PollJson.encodeToString(Record.serializer(), r)).jsonObject

    @Test fun emptyRecordStillWritesContractKeys() {
        val o = encode(TestData.emptyRecord())
        assertEquals("1", o.getValue("schemaVersion").jsonPrimitive.content)
        assertEquals("draft", o.getValue("status").jsonPrimitive.content)
        for (k in listOf("values", "tables", "signatures")) assertEquals(0, o.getValue(k).jsonObject.size)
        assertTrue("null keys stay omitted", "completedAt" !in o)
    }

    @Test fun rowAlwaysWritesValues() {
        val o = encode(TestData.emptyRecord().copy(tables = mapOf("T" to listOf(Row("r1")))))
        val row = (o.getValue("tables").jsonObject.getValue("T") as JsonArray).single().jsonObject
        assertEquals(0, row.getValue("values").jsonObject.size)
    }

    @Test fun signatureWithoutNameOmitsNameKey() {
        val o = encode(TestData.emptyRecord().copy(signatures = mapOf("s" to SignatureValue("media/sig_s.png", null, "2026-01-01T09:00:00+07:00"))))
        assertTrue("name" !in o.getValue("signatures").jsonObject.getValue("s").jsonObject)
    }

    @Test fun readingIsTolerant() {
        val r = PollJson.decodeFromString(Record.serializer(), """{"id":"x"}""")
        assertEquals("draft", r.status)
        assertTrue(r.values.isEmpty() && r.tables.isEmpty() && r.signatures.isEmpty())
        val withBadMedia = PollJson.decodeFromString(
            Record.serializer(),
            """{"id":"x","values":{"f":{"media":[{"file":"media/a.jpg"},{"nofile":1},{"file":"media/b.jpg","lat":"bad"}]}}}""",
        )
        assertEquals(listOf("media/a.jpg"), withBadMedia.values.getValue("f").media.map { it.file })
        val noSeconds = PollJson.decodeFromString(Record.serializer(), """{"id":"x","createdAt":"2026-10-23T10:00+07:00"}""")
        assertEquals("2026-10-23T10:00+07:00", noSeconds.createdAt) // raw string kept
    }

    @Test fun timestampsAlwaysHaveSeconds() {
        val re = Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(Z|[+-]\d{2}:\d{2})""")
        assertTrue(re.matches(nowIso()))
        val atSecondZero = OffsetDateTime.parse("2026-10-23T10:00+07:00").withNano(0)
        assertEquals("2026-10-23T10:00:00+07:00", toIso(atSecondZero))
        assertEquals("2026-10-23T10:00:00+07:00", toIso(OffsetDateTime.parse("2026-10-23T10:00:00.123456+07:00").withNano(0)))
    }

    @Test fun namingUsesRawCodeCollapsesRunsAndFallsBackToRecordId() {
        val t = TestData.template
        val r = TestData.emptyRecord().copy(values = mapOf("HD-code" to FieldValue(v = kotlinx.serialization.json.JsonPrimitive("HD  01 // A"))))
        assertEquals("HD-01-A_P01-04_01.jpg", mediaFileName(t, r, "P01-04", 1, "jpg"))
        assertEquals("rec-1_E1-mode_02.mov", mediaFileName(t, TestData.emptyRecord(), "E1-mode", 2, "mov"))
        assertEquals("sig_a-b.png", signatureFileName("a b"))
        assertEquals("sig_surveyor.png", signatureFileName("surveyor"))
    }
}

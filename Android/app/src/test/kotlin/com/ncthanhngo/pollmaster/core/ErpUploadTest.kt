package com.ncthanhngo.pollmaster.core

import com.ncthanhngo.pollmaster.core.export.ErpUpload
import com.ncthanhngo.pollmaster.core.format.displayDateTimeDashed
import com.ncthanhngo.pollmaster.core.model.PollJson
import com.ncthanhngo.pollmaster.core.model.Record
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class ErpUploadTest {
    private val t = TestData.template

    @Test fun fieldsForDraftOmitEmptyOptionalKeys() {
        val r = TestData.emptyRecord(t).copy(values = mapOf("HD-code" to TestData.text(" HD 01/A ")))
        val f = ErpUpload.fields(t, r).toMap()
        assertEquals("rec-1", f["record_id"])
        assertEquals("HD 01/A", f["record_code"]) // raw, not the file-name form
        assertEquals(t.id, f["template_id"])
        assertEquals(t.version, f["template_version"])
        assertEquals(t.title, f["template_title"])
        assertEquals("draft", f["status"])
        assertEquals("2026-01-01T08:00:00+07:00", f["recorded_at"])
        assertFalse("completed_at" in f)
        assertTrue(f["title"]!!.isNotEmpty())
    }

    @Test fun fieldsForCompletedCarryCompletedAtAndNoCodeWhenEmpty() {
        val r = TestData.emptyRecord(t).copy(status = Record.STATUS_COMPLETED, completedAt = "2026-01-02T09:00:00+07:00")
        val f = ErpUpload.fields(t, r).toMap()
        assertEquals("completed", f["status"])
        assertEquals("2026-01-02T09:00:00+07:00", f["completed_at"])
        assertFalse("record_code" in f)
        assertEquals("record_id", ErpUpload.fields(t, r).first().first)
    }

    @Test fun changedSinceUploadRule() {
        val r = TestData.emptyRecord(t)
        assertFalse(ErpUpload.hasUnsentChanges(r)) // never sent
        val sent = r.copy(erpUploadedAt = "2026-01-01T08:00:00+07:00")
        assertFalse(ErpUpload.hasUnsentChanges(sent)) // same instant
        assertTrue(ErpUpload.hasUnsentChanges(sent.copy(updatedAt = "2026-01-01T08:00:01+07:00")))
        assertFalse(ErpUpload.hasUnsentChanges(sent.copy(updatedAt = "2026-01-01T00:59:00Z"))) // other offset, earlier instant
    }

    @Test fun stampOnlyWhileRecordStaysInItsProject() {
        val r = TestData.emptyRecord(t).copy(projectId = "p1")
        assertTrue(ErpUpload.shouldStamp(r, r.copy(updatedAt = "later")))
        assertFalse(ErpUpload.shouldStamp(r, r.copy(projectId = "p2")))
        assertFalse(ErpUpload.shouldStamp(r, null))
    }

    @Test fun uploadedAtRoundTripsAndIsOptional() {
        val r = TestData.emptyRecord(t).copy(erpUploadedAt = "2026-01-03T10:00:00+07:00")
        val back = PollJson.decodeFromString(Record.serializer(), PollJson.encodeToString(Record.serializer(), r))
        assertEquals(r.erpUploadedAt, back.erpUploadedAt)
        assertEquals(null, PollJson.decodeFromString(Record.serializer(), """{"id":"x"}""").erpUploadedAt)
    }

    @Test fun markerDateUsesDashes() {
        assertEquals("03-01-2026 10:00", displayDateTimeDashed("2026-01-03T10:00:00+07:00", ZoneOffset.ofHours(7)))
    }
}

package com.ncthanhngo.pollmaster.core.templateeditor

import com.ncthanhngo.pollmaster.core.TestData
import com.ncthanhngo.pollmaster.core.storage.TemplateParser
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TemplateDraftStoreTest {
    private fun draft(id: String, base: String? = "T-1", updated: String = "2026-10-27T10:00:00Z") = TemplateDraft(
        draftId = id, baseId = base, baseVersion = if (base == null) null else "1.0", projectId = "P1", createdAt = "2026-10-27T09:00:00Z", updatedAt = updated,
        content = JsonObject(mapOf("id" to JsonPrimitive(base ?: "NEW-1"), "version" to JsonPrimitive("1.0"), "title" to JsonPrimitive("Mẫu"), "x-custom" to JsonPrimitive(2))),
    )

    @Test fun saveLoadListDelete() {
        val store = TemplateDraftStore(TestData.tempDir())
        assertTrue(store.list().isEmpty())
        val a = draft("d1").copy(publishVersion = "1.1")
        store.save(a)
        store.save(draft("d2", base = null, updated = "2026-10-27T11:00:00Z"))
        assertEquals(a, store.load("d1"))
        assertEquals(listOf("d2", "d1"), store.list().map { it.draftId })
        store.delete("d1")
        assertNull(store.load("d1"))
        store.delete("d1")
        assertEquals(listOf("d2"), store.list().map { it.draftId })
    }

    @Test fun overwriteKeepsOneFileAndNullsRoundTrip() {
        val store = TemplateDraftStore(TestData.tempDir())
        val d = draft("d1", base = null)
        store.save(d)
        store.save(d.copy(content = JsonObject(mapOf("id" to JsonPrimitive("NEW-2"), "title" to JsonPrimitive("Đổi")))))
        val back = store.load("d1")!!
        assertTrue(back.baseId == null && back.baseVersion == null && back.publishVersion == null)
        assertEquals("NEW-2", back.templateId)
        assertEquals(1, store.list().size)
    }

    @Test fun oneDraftPerTemplateId() {
        val store = TemplateDraftStore(TestData.tempDir())
        store.save(draft("d1", base = "T-1"))
        store.save(draft("d2", base = null))
        assertEquals("d1", store.existing("T-1")?.draftId)
        assertEquals("d2", store.existing("NEW-1")?.draftId)
        assertNull(store.existing("T-9"))
    }

    @Test fun brokenFilesAreSkipped() {
        val root = TestData.tempDir()
        val store = TemplateDraftStore(root)
        store.save(draft("d1"))
        File(root, "template-drafts/bad.json").writeText("{not json")
        File(root, "template-drafts/partial.json").writeText("{\"draftId\":\"x\"}")
        assertEquals(listOf("d1"), store.list().map { it.draftId })
    }

    @Test fun blankDraftIsAValidNewTemplate() {
        val d = TemplateDraft.blank("P1", emptySet(), "t0") { "ab12" }
        assertTrue(d.baseId == null && d.templateId == "MAUMOI-AB12" && d.publishVersion == null)
        assertTrue(d.doc.let { TemplateLint.check(it, null).none { i -> i.severity == TemplateIssue.Severity.ERROR } })
        assertEquals(emptyList<String>(), TemplateParser.problems(d.doc.pretty()))
    }

    @Test fun cloneGetsNewCodeVersionAndTitle() {
        val source = TemplateDoc.of(TestData.templateText)
        val d = TemplateDraft.clone(source.root, "P1", setOf("BBTDP-AB12"), "t0") { "ab12" }
        val o = (d.content as JsonObject)
        assertNotEquals("BB-TDP-01", d.templateId)
        assertTrue(d.baseId == null && (o["version"] as JsonPrimitive).content == "1.0")
        assertTrue((o["title"] as JsonPrimitive).content.endsWith("(bản sao)"))
        assertEquals((source.root as JsonObject)["sections"], o["sections"])
        assertTrue(TemplateLint.check(d.doc, null).none { it.severity == TemplateIssue.Severity.ERROR })
    }
}

package com.ncthanhngo.pollmaster.core

import com.ncthanhngo.pollmaster.core.model.Field
import com.ncthanhngo.pollmaster.core.model.HeadingItem
import com.ncthanhngo.pollmaster.core.model.TableItem
import com.ncthanhngo.pollmaster.core.model.allFields
import com.ncthanhngo.pollmaster.core.model.fieldCount
import com.ncthanhngo.pollmaster.core.storage.TemplateException
import com.ncthanhngo.pollmaster.core.storage.TemplateParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TemplateDecodeTest {
    private val t = TestData.template

    @Test fun decodesMetadataAndAllItems() {
        assertEquals("BB-TDP-01", t.id)
        assertEquals(5, t.header.size)
        assertEquals(10, t.sections.size)
        assertEquals(2, t.signatures.size)
        val items = t.sections.flatMap { it.items }
        assertEquals(4, items.filterIsInstance<TableItem>().size)
        assertEquals(6, items.filterIsInstance<HeadingItem>().size)
        assertEquals(t.sections.sumOf { it.items.size }, items.size)
    }

    @Test fun fieldIdsAreUnique() {
        val ids = t.allFields().map { it.id } + t.sections.flatMap { it.items.filterIsInstance<TableItem>() }.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(t.fieldCount() > 150)
    }

    @Test fun decodesSpecialFeatures() {
        val fields = t.allFields().associateBy { it.id }
        assertEquals(3, fields.getValue("G-20").samples)
        assertTrue(fields.getValue("B-06").parts.isNotEmpty())
        assertEquals("composite", fields.getValue("B-06").type)
        assertTrue(fields.values.filterIsInstance<Field>().any { it.attachPhoto })
        val e2 = t.sections.flatMap { it.items }.filterIsInstance<TableItem>().first { it.id == "E2" }
        assertTrue(e2.isFixed)
        val f1 = t.sections.flatMap { it.items }.filterIsInstance<TableItem>().first { it.id == "F1" }
        assertEquals(8, f1.stopwatch?.columns?.size)
    }

    @Test fun reportsPathOnBadTemplate() {
        try {
            TemplateParser.parse("""{"id":"X","version":"1","title":"t","sections":[{"id":"A","title":"a","items":[{"id":"1","label":"x"}]}]}""")
            fail("expected exception")
        } catch (e: TemplateException) {
            assertTrue(e.message!!.contains("type") || e.message!!.contains("\$.sections"))
        }
    }

    @Test fun rejectsDuplicateIds() {
        val json = """{"id":"X","version":"1","title":"t","header":[{"id":"a","label":"A","type":"text"},{"id":"a","label":"B","type":"text"}]}"""
        try {
            TemplateParser.parse(json)
            fail("expected exception")
        } catch (e: TemplateException) {
            assertTrue(e.message!!.contains("trùng"))
        }
    }
}

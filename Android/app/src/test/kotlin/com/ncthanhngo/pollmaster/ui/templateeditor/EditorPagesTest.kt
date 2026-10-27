package com.ncthanhngo.pollmaster.ui.templateeditor

import com.ncthanhngo.pollmaster.core.templateeditor.TemplateDoc
import com.ncthanhngo.pollmaster.core.templateeditor.docPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EditorPagesTest {
    private val shared = File("../../shared")
    private val doc = TemplateDoc.of(File(shared, "templates/bb-tdp-01.json").readText())

    private fun tableIndex(): Pair<Int, Int> {
        for (s in 0..30) for (i in 0..60) {
            val n = doc.node(docPath("sections", s, "items", i)) ?: continue
            if (n.toString().contains("\"kind\":\"table\"")) return s to i
        }
        error("no table in the sample template")
    }

    @Test fun templateLevelKeyOpensInfo() {
        assertEquals(listOf(EditorPage.Index, EditorPage.Info), EditorPages.stackFor(doc, "title"))
        assertEquals(listOf(EditorPage.Index, EditorPage.Info), EditorPages.stackFor(doc, "id"))
    }

    @Test fun sectionsAloneStaysOnIndex() {
        assertEquals(listOf<EditorPage>(EditorPage.Index), EditorPages.stackFor(doc, "sections"))
    }

    @Test fun headerFieldOpensItsForm() {
        assertEquals(
            listOf(EditorPage.Index, EditorPage.Items(docPath("header")), EditorPage.Item(docPath("header"), 0)),
            EditorPages.stackFor(doc, "header[0].label"),
        )
    }

    @Test fun sectionItemOpensSectionThenItem() {
        val list = docPath("sections", 0, "items")
        assertEquals(
            listOf(EditorPage.Index, EditorPage.Items(list), EditorPage.Item(list, 1)),
            EditorPages.stackFor(doc, "sections[0].items[1].options[0]"),
        )
    }

    @Test fun columnOpensTableThenColumns() {
        val (s, i) = tableIndex()
        val items = docPath("sections", s, "items")
        val columns = docPath("sections", s, "items", i, "columns")
        val stack = EditorPages.stackFor(doc, "sections[$s].items[$i].columns[1].type")
        assertEquals(listOf(EditorPage.Index, EditorPage.Items(items), EditorPage.Item(items, i), EditorPage.Items(columns), EditorPage.Item(columns, 1)), stack)
    }

    @Test fun missingNodeStopsAtTheLastThatExists() {
        val stack = EditorPages.stackFor(doc, "sections[0].items[999].label")
        assertEquals(EditorPage.Items(docPath("sections", 0, "items")), stack.last())
        assertEquals(listOf<EditorPage>(EditorPage.Index), EditorPages.stackFor(doc, "sections[99].title"))
    }

    @Test fun brokenJsonIsAnErrorUnderTheNode() {
        val ok = checkJson(doc, doc, false, emptyList(), doc.pretty())
        assertNull(ok.error)
        assertFalse(ok.idRemoved)
        assertTrue(checkJson(doc, doc, false, emptyList(), "{ \"id\": ").error!!.startsWith("JSON không hợp lệ"))
        assertEquals("Nội dung phải là một đối tượng JSON { … }.", checkJson(doc, doc, false, emptyList(), "[1]").error)
    }

    @Test fun dropAnExistingCodeWarnsAboutIt() {
        val code = doc.node(docPath("header", 0, "id"))!!.toString().trim('"')
        val text = doc.pretty().replace("\"$code\"", "\"RENAMED-1\"")
        assertTrue(checkJson(doc, doc, false, emptyList(), text).idRemoved)
    }
}

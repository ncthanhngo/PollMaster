package com.ncthanhngo.pollmaster.core

import com.ncthanhngo.pollmaster.core.storage.TemplateException
import com.ncthanhngo.pollmaster.core.storage.TemplateParser
import com.ncthanhngo.pollmaster.core.storage.TemplateText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TemplateTextTest {
    @Test fun plainJson() {
        assertEquals("{\"a\": 1}", TemplateText.extractJson("  {\"a\": 1}\n"))
    }

    @Test fun fencedWithProse() {
        val text = "Đây là mẫu:\n```json\n{\"a\": {\"b\": 2}}\n```\nGiả định: không có {gì}."
        assertEquals("{\"a\": {\"b\": 2}}", TemplateText.extractJson(text))
    }

    @Test fun firstJsonFenceWins() {
        val text = "```text\nx\n```\n```json\n{\"n\": 1}\n```\n```json\n{\"n\": 2}\n```"
        assertEquals("{\"n\": 1}", TemplateText.extractJson(text))
    }

    @Test fun proseAroundUnfencedJson() {
        assertEquals("{\"a\":1}", TemplateText.extractJson("Kết quả: {\"a\":1} xong"))
    }

    @Test fun noJson() {
        assertNull(TemplateText.extractJson("không có gì ở đây"))
        assertNull(TemplateText.extractJson("```json\n```"))
        assertNull(TemplateText.extractJson(""))
    }

    @Test fun errorReportFormat() {
        assertEquals("P:\n- e1\n- e2", TemplateText.errorReport("P:", "e1\n\ne2\n"))
    }

    @Test fun renameChangesOnlyTitle() {
        val before = TemplateParser.parse(TestData.templateText)
        val after = TemplateParser.parse(TemplateText.renamed(TestData.templateText, "  Tủ đổi pin Hà Nội "))
        assertEquals("Tủ đổi pin Hà Nội", after.title)
        assertEquals(before.copy(title = after.title), after)
    }

    @Test(expected = TemplateException::class)
    fun renameRejectsBlankTitle() {
        TemplateText.renamed(TestData.templateText, "   ")
    }

    @Test fun guideExampleParses() {
        val guide = File("../../shared/huong-dan-tao-mau-bang-ai.md").readText()
        val section = guide.substringAfter("## 7.")
        val json = TemplateText.extractJson(section)!!
        assertTrue(TemplateParser.parse(json).sections.isNotEmpty())
    }
}

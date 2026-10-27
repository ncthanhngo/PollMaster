package com.ncthanhngo.pollmaster.core

import com.ncthanhngo.pollmaster.core.project.Project
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

    @Test fun editChangesOnlyNameShortNameAndColor() {
        val before = TemplateParser.parse(TestData.templateText)
        val after = TemplateParser.parse(TemplateText.edited(TestData.templateText, "  Tủ đổi pin Hà Nội ", " TĐP ", "#B8312F"))
        assertEquals("Tủ đổi pin Hà Nội", after.title)
        assertEquals("TĐP", after.badge)
        assertEquals("#B8312F", after.badgeColor)
        assertEquals(before.copy(title = after.title, shortName = after.shortName, color = after.color), after)
        val cleared = TemplateParser.parse(TemplateText.edited(TemplateText.edited(TestData.templateText, "X", "S", "#000000"), "X", "", null))
        assertNull(cleared.shortName)
        assertNull(cleared.color)
        assertEquals(cleared.id, cleared.badge)
        assertTrue(cleared.badgeColor in Project.COLORS)
    }

    @Test(expected = TemplateException::class)
    fun editRejectsBlankTitle() {
        TemplateText.edited(TestData.templateText, "   ", null, null)
    }

    @Test(expected = TemplateException::class)
    fun editRejectsBadColor() {
        TemplateText.edited(TestData.templateText, "A", null, "red")
    }

    @Test fun defaultBadgeColorMatchesIos() {
        // same hash rule as Template.badgeColor on iOS: h = (h * 31 + byte) % 1000003: "BB-TDP-01" -> 9, "KS-VF-SWAP-01" -> 7
        assertEquals(Project.COLORS[9], TemplateParser.parse(TestData.templateText).badgeColor)
    }

    @Test fun guideExampleParses() {
        val guide = File("../../shared/huong-dan-tao-mau-bang-ai.md").readText()
        val section = guide.substringAfter("## 7.")
        val json = TemplateText.extractJson(section)!!
        assertTrue(TemplateParser.parse(json).sections.isNotEmpty())
    }
}

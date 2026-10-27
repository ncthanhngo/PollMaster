package com.ncthanhngo.pollmaster.core

import com.ncthanhngo.pollmaster.core.editor.mediaFileName
import com.ncthanhngo.pollmaster.core.editor.nextMediaName
import com.ncthanhngo.pollmaster.core.format.formatElapsed
import com.ncthanhngo.pollmaster.core.format.formatNumber
import com.ncthanhngo.pollmaster.core.format.nextRecordSeq
import com.ncthanhngo.pollmaster.core.format.recordTitle
import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {
    @Test fun numbersRoundHalfAwayAndStripZeros() {
        assertEquals("2.35", formatNumber(2.345, 2))
        assertEquals("-3", formatNumber(-2.5, 0))
        assertEquals("12.5", formatNumber(12.50, 2))
        assertEquals("0", formatNumber(-0.001, 2))
    }

    @Test fun elapsedIsMinutesSeconds() {
        assertEquals("01:05", formatElapsed(65.0))
        assertEquals("1:40:00", formatElapsed(6000.0))
        assertEquals("59:59", formatElapsed(3599.0))
    }

    @Test fun mediaNamesFollowPatternAndAvoidCollisions() {
        val t = TestData.template
        val r = TestData.emptyRecord().copy(values = mapOf("HD-code" to TestData.text("HD 01/A")))
        assertEquals("HD-01-A_P01-04_01.jpg", mediaFileName(t, r, "P01-04", 1, "jpg"))
        assertEquals("HD-01-A_P01-04_02.jpg", nextMediaName(t, r, "P01-04", "jpg", setOf("HD-01-A_P01-04_01.jpg")))
    }

    @Test fun recordTitleIsNumberTemplateTitleAndDay() {
        val t = TestData.template
        val r = TestData.emptyRecord().copy(values = mapOf("A-01" to TestData.text("Trạm 7")))
        assertEquals("${t.title} 01/01/2026", recordTitle(t, r)) // made before numbering
        assertEquals("01 - ${t.title} 01/01/2026", recordTitle(t, r.copy(seq = 1)))
        assertEquals("12 - ${t.title} 01/01/2026", recordTitle(t, r.copy(seq = 12)))
    }

    @Test fun nextSeqCountsPerProjectAndTemplate() {
        val r = TestData.emptyRecord()
        val list = listOf(r.copy(projectId = "p", seq = 1), r.copy(projectId = "p", seq = 3), r.copy(projectId = "q", seq = 9),
            r.copy(projectId = "p", templateId = "other", seq = 7), r.copy(projectId = "p"))
        assertEquals(4, nextRecordSeq(list, "p", r.templateId)) // the gap left by a deleted record is not reused
        assertEquals(1, nextRecordSeq(list, "z", r.templateId))
    }
}

package com.ncthanhngo.pollmaster.core

import com.ncthanhngo.pollmaster.core.editor.mediaFileName
import com.ncthanhngo.pollmaster.core.editor.nextMediaName
import com.ncthanhngo.pollmaster.core.format.formatElapsed
import com.ncthanhngo.pollmaster.core.format.formatNumber
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

    @Test fun recordTitleUsesPatternAndFallsBack() {
        val t = TestData.template
        val empty = TestData.emptyRecord()
        assertEquals("Phiếu mới · 01/01/2026 08:00", recordTitle(t, empty))
        val r = empty.copy(values = mapOf("A-03" to TestData.text("12 Lê Lợi"), "A-01" to TestData.text("Trạm 7")))
        assertEquals("Trạm 7 · 12 Lê Lợi", recordTitle(t, r))
        assertEquals("Trạm 7", recordTitle(t, empty.copy(values = mapOf("A-01" to TestData.text("Trạm 7")))))
    }
}

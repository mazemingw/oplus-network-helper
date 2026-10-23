package com.nvmex.networkhelper.util.network

import org.junit.Assert.assertEquals
import org.junit.Test

class CellTypeFormatTest {
    @Test fun metroChannelIsDistinctFromHighSpeedRail() {
        assertEquals("NR 地铁专网", formatCellType("NR", 520830))
        listOf(507150, 527070, 627744, 634464, 423630).forEach {
            assertEquals("NR 高铁专网", formatCellType("NR", it))
        }
    }
    @Test fun labelsOnlyApplyToTheActualNrCell() {
        assertEquals("LTE", formatCellType("LTE", 520830))
        assertEquals("NR", formatCellType("NR", 520831))
        assertEquals("NR", formatCellType("NR", null))
        assertEquals("-", formatCellType("", null))
    }
}

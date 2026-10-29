package com.nvmex.networkhelper.ui.settings.fonts

import androidx.compose.ui.unit.*
import org.junit.Assert.*
import org.junit.Test

class DisplayDensityTest {
    @Test fun displayScalingChangesDpAndSpBySameFactorInsteadOfOnlyText() {
        val system = Density(3f, 1.5f)
        for (level in FontSizeConfig.FontSizeLevel.entries) {
            val app = displayDensity(system, level.scale)
            assertEquals(3f * level.scale, app.density, .0001f)
            assertEquals(1.5f, app.fontScale, 0f)
            assertEquals(with(system) { 100.dp.toPx() } * level.scale, with(app) { 100.dp.toPx() }, .001f)
            assertEquals(with(system) { 14.sp.toPx() } * level.scale, with(app) { 14.sp.toPx() }, .001f)
        }
    }

    @Test fun densityResizeExpandsAvailableLayoutSpaceAndCoordinateRoundTrips() {
        val app = displayDensity(Density(3f), .8f)
        assertEquals(450f, with(app) { 1080f.toDp().value }, .001f)
        assertEquals(42f, with(app) { 42.dp.toPx().toDp().value }, .001f)
    }

    @Test fun systemNonlinearFontConversionIsPreserved() {
        val system = object : Density {
            override val density = 3f
            override val fontScale = 2f
            override fun TextUnit.toDp() = (value * 1.4f).dp
            override fun Dp.toSp() = (value / 1.4f).sp
        }
        val app = displayDensity(system, .9f)
        assertEquals(with(system) { 20.sp.toPx() } * .9f, with(app) { 20.sp.toPx() }, .001f)
        assertEquals(20f, with(app) { 20.sp.toDp().toSp().value }, .001f)
    }

    @Test fun standardAndCorruptPreferencesDoNotMagnifyOrDoubleScale() {
        assertEquals(FontSizeConfig.FontSizeLevel.MEDIUM, displaySizeLevel(null))
        assertEquals(FontSizeConfig.FontSizeLevel.MEDIUM, displaySizeLevel("corrupt"))
        val base = Density(3f, 1.2f)
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, 0f, 3f))
            assertEquals(3f, displayDensity(base, bad).density, 0f)
        assertEquals(1f, FontSizeConfig.FontSizeLevel.MEDIUM.scale, 0f)
    }
}

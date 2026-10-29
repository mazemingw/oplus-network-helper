package com.nvmex.networkhelper.ui.settings.fonts

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit

internal fun displaySizeLevel(name: String?): FontSizeConfig.FontSizeLevel =
    FontSizeConfig.FontSizeLevel.entries.firstOrNull { it.name == name } ?: FontSizeConfig.FontSizeLevel.MEDIUM

/** Scale layout and text together, while retaining the system's accessibility font conversion. */
internal fun displayDensity(base: Density, scale: Float): Density = object : Density {
    override val density = base.density * scale.takeIf { it.isFinite() && it in 0.8f..1.3f }.let { it ?: 1f }
    override val fontScale = base.fontScale
    override fun TextUnit.toDp(): Dp = with(base) { this@toDp.toDp() }
    override fun Dp.toSp(): TextUnit = with(base) { this@toSp.toSp() }
}

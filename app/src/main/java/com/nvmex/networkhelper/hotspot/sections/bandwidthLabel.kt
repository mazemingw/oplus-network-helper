package com.nvmex.networkhelper.hotspot.sections

import com.nvmex.networkhelper.hotspot.ChannelWidthCompat

fun bandwidthLabel(width: Int): String {
    return ChannelWidthCompat.label(width)
}

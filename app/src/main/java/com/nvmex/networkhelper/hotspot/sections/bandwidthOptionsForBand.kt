package com.nvmex.networkhelper.hotspot.sections

import com.nvmex.networkhelper.hotspot.ChannelWidthCompat

 fun bandwidthOptionsForBand(band: Int): List<Int> {
    return ChannelWidthCompat.optionsForBand(band)
}
package com.nvmex.networkhelper.hotspot.sections

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.nvmex.networkhelper.hotspot.ChannelOption

@Composable
 fun ChannelListBlock(title: String, channels: List<ChannelOption>) {
    Text(
        "$title：${
            if (channels.isEmpty()) "-"
            else channels.joinToString { it.display }
        }"
    )
}
package com.nvmex.networkhelper.ui.wifi.sections

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nvmex.networkhelper.model.wifi.WifiUiModel

@Composable
fun WifiInfoCard(
    ui: WifiUiModel,
    routerIconRes: Int,
    phoneIconRes: Int,
    modifier: Modifier = Modifier,
    deviceLine: String = remember { "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}" }
) {
    Card(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {

            // ========== Left: Router ==========
            RouterColumn(
                gateway = ui.gateway,
                standardLabel = ui.standardLabel,
                iconRes = routerIconRes,
                channelWidthMhz = ui.channelWidthMhz,
            modifier = Modifier.weight(1f)
            )

            // ========== Middle: WiFi core ==========
            WifiCoreColumn(
                ssid = ui.ssid,
                rssiDbm = ui.rssiDbm,
                linkSpeedMbps = ui.linkSpeedMbps,
                channel = ui.channel,
                frequencyMhz = ui.frequencyMhz,
                modifier = Modifier.weight(2.2f)
            )

            // ========== Right: Phone ==========
            PhoneColumn(
                localIp = ui.localIp,
                deviceLine = deviceLine,
                iconRes = phoneIconRes,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

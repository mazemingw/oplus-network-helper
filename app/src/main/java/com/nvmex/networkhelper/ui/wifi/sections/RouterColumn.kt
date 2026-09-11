package com.nvmex.networkhelper.ui.wifi.sections

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nvmex.networkhelper.R

@Composable
fun RouterColumn(
    gateway: String,
    standardLabel: String,
    iconRes: Int,
    modifier: Modifier = Modifier,
    channelWidthMhz: Int? = null
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        DrawableIconOrPlaceholder(resId = iconRes, sizeDp = 34)

        Text(
            text = gateway,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Text(
            text = buildString {
//                append("802.11 ")
                append(standardLabel)
                channelWidthMhz?.let { append(" · "); append(it); append("MHz") }
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

    }
}

@Composable
fun WifiCoreColumn(
    ssid: String,
    rssiDbm: Int?,
    linkSpeedMbps: Int?,
    channel: Int?,
    frequencyMhz: Int?,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = ssid,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Text(
            text = rssiDbm?.let { "$it dBm" } ?: "- dBm",
            style = MaterialTheme.typography.titleSmall
        )

        val rateText = linkSpeedMbps?.let { "${it} Mbps" } ?: "-"
        val chText = channel?.let { "CH $it" } ?: "CH -"
        val freqText = frequencyMhz?.let { "${it} MHz" } ?: "- MHz"

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = stringResource(R.string.wifi_rate, rateText),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )

            Text(
                text = "$chText · $freqText",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun PhoneColumn(
    localIp: String,
    deviceLine: String,
    iconRes: Int,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        DrawableIconOrPlaceholder(resId = iconRes, sizeDp = 34)

        Text(
            text = localIp,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Text(
            text = deviceLine,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
@Composable
private fun DrawableIconOrPlaceholder(@DrawableRes resId: Int, sizeDp: Int) {
    if (resId != 0) {
        Icon(
            painter = painterResource(resId),
            contentDescription = null,
            modifier = Modifier.size(sizeDp.dp)
        )
    } else {
        // 资源缺失：不崩溃，给个空盒子占位
        Box(Modifier.size(sizeDp.dp))
    }
}

package com.nvmex.networkhelper.ui.wifi.sections

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.nvmex.networkhelper.R
import com.nvmex.networkhelper.ui.navigation.Routes


@Composable
fun QuickControlPanel(
    navController: NavHostController,
    hotspotRunning: Boolean,
    serverRunning: Boolean,
    clientRunning: Boolean,
    modifier: Modifier = Modifier,
) {
    val activeBlue = Color(0xFF448AFF)

    @Composable
    fun IconTile(
        label: String,
        painterId: Int,
        active: Boolean,
        modifier: Modifier = Modifier,
        onClick: () -> Unit
    ) {
        val tint = if (active) activeBlue else MaterialTheme.colorScheme.onSurfaceVariant

        Surface(
            modifier = modifier
                .fillMaxWidth()
                .height(70.dp)
                .clickable(onClick = onClick),
            shape = RoundedCornerShape(18.dp),
            color = if (active) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.34f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f)
            },
            border = BorderStroke(
                0.8.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f)
            )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        painter = painterResource(painterId),
                        contentDescription = label,
                        tint = tint,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
        }

    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.48f),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = BorderStroke(
            0.8.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.32f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            IconTile(
                label = stringResource(R.string.wifi_quick_hotspot),
                painterId = if (hotspotRunning) R.drawable.wifi_tethering else R.drawable.wifi_tethering_off,
                active = hotspotRunning,
                modifier = Modifier.weight(1f),
                onClick = { navController.navigate(Routes.HOTSPOT) }
            )

            IconTile(
                label = stringResource(R.string.wifi_quick_server),
                painterId = R.drawable.speed,
                active = serverRunning,
                modifier = Modifier.weight(1f),
                onClick = { navController.navigate(Routes.IPERF_SERVER) }
            )

            IconTile(
                label = stringResource(R.string.wifi_quick_client),
                painterId = R.drawable.avg_pace,
                active = clientRunning,
                modifier = Modifier.weight(1f),
                onClick = { navController.navigate(Routes.IPERF_CLIENT) }
            )
        }
    }
}




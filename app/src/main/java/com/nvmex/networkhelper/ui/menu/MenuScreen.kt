@file:OptIn(ExperimentalMaterial3Api::class)

package com.nvmex.networkhelper.ui.menu

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.Domain
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.SettingsEthernet
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.nvmex.networkhelper.R
import com.nvmex.networkhelper.model.network.NetworkPanelUiState
import com.nvmex.networkhelper.ui.map.AmapMapActivity
import com.nvmex.networkhelper.ui.navigation.Routes

@Composable
fun MenuScreen(
    panel: NetworkPanelUiState,
    navController: NavHostController,
    contentBottomPadding: Dp = 0.dp,
) {
    val context = LocalContext.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.nav_menu),
                style = MaterialTheme.typography.headlineSmall
            )

            Spacer(Modifier.height(6.dp))


            Spacer(Modifier.height(18.dp))

            MenuSectionCard(
                title = stringResource(R.string.menu_stable_title),
                subtitle = stringResource(R.string.menu_stable_subtitle),
                leadingIcon = null
            ) {
                MenuEntryItem(
                    icon = {
                        Icon(
                            imageVector = Icons.Default.CellTower,
                            contentDescription = null
                        )
                    },
                    title = stringResource(R.string.menu_cell_query_title),
                    subtitle = stringResource(R.string.menu_cell_query_subtitle),
                    onClick = { navController.navigate(Routes.CELL_QUERY) }
                )

                MenuEntryDivider()

                MenuEntryItem(
                    icon = {
                        Icon(
                            imageVector = Icons.Default.Router,
                            contentDescription = null
                        )
                    },
                    title = stringResource(R.string.menu_hotspot_title),
                    subtitle = stringResource(R.string.menu_hotspot_subtitle),
                    onClick = {
                        navController.navigate(Routes.HOTSPOT) {
                            launchSingleTop = true
                        }
                    }
                )

                MenuEntryDivider()

                MenuEntryItem(
                    icon = {
                        Icon(
                            imageVector = Icons.Default.NetworkCheck,
                            contentDescription = null
                        )
                    },
                    title = stringResource(R.string.menu_iperf_client_title),
                    subtitle = stringResource(R.string.menu_iperf_client_subtitle),
                    onClick = {
                        navController.navigate(Routes.IPERF_CLIENT) {
                            launchSingleTop = true
                        }
                    }
                )

                MenuEntryDivider()

                MenuEntryItem(
                    icon = {
                        Icon(
                            imageVector = Icons.Default.SettingsEthernet,
                            contentDescription = null
                        )
                    },
                    title = stringResource(R.string.menu_iperf_server_title),
                    subtitle = stringResource(R.string.menu_iperf_server_subtitle),
                    onClick = {
                        navController.navigate(Routes.IPERF_SERVER) {
                            launchSingleTop = true
                        }
                    }
                )

                MenuEntryDivider()

                MenuEntryItem(
                    icon = {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = null
                        )
                    },
                    title = stringResource(R.string.menu_import_cell_params_title),
                    subtitle = stringResource(R.string.menu_import_cell_params_subtitle),
                    onClick = { navController.navigate(Routes.IMPORT_CELL_PARAMS) }
                )
            }

            Spacer(Modifier.height(16.dp))

            MenuSectionCard(
                title = stringResource(R.string.menu_dev_title),
                subtitle = stringResource(R.string.menu_dev_subtitle),
                leadingIcon = null
            ) {
                MenuEntryItem(
                    icon = {
                        Icon(
                            imageVector = Icons.Default.Map,
                            contentDescription = null
                        )
                    },
                    title = stringResource(R.string.menu_drive_map_title),
                    subtitle = stringResource(R.string.menu_drive_map_subtitle),
                    onClick = {
                        context.startActivity(
                            Intent(context, AmapMapActivity::class.java)
                        )
                    }
                )

                MenuEntryDivider()

                MenuEntryItem(
                    icon = {
                        Icon(
                            imageVector = Icons.Default.Domain,
                            contentDescription = null
                        )
                    },
                    title = stringResource(R.string.menu_indoor_drive_title),
                    subtitle = stringResource(R.string.menu_indoor_drive_subtitle),
                    onClick = {
                        navController.navigate(Routes.INDOOR_DRIVE_TEST) {
                            launchSingleTop = true
                        }
                    }
                )

                MenuEntryDivider()

                MenuEntryItem(
                    icon = {
                        Icon(
                            imageVector = Icons.Default.BugReport,
                            contentDescription = null
                        )
                    },
                    title = stringResource(R.string.menu_plugin_share_title),
                    subtitle = stringResource(R.string.menu_plugin_share_subtitle),
                    onClick = {
                        navController.navigate(Routes.PLUGIN_SHARE) {
                            launchSingleTop = true
                        }
                    }
                )

                MenuEntryDivider()

                MenuEntryItem(
                    icon = {
                        Icon(
                            imageVector = Icons.Default.SettingsEthernet,
                            contentDescription = null
                        )
                    },
                    title = stringResource(R.string.menu_frequency_calculator_title),
                    subtitle = stringResource(R.string.menu_frequency_calculator_subtitle),
                    onClick = {
                        navController.navigate(Routes.FREQUENCY_CALCULATOR) {
                            launchSingleTop = true
                        }
                    }
                )

                MenuEntryDivider()

                MenuEntryItem(
                    icon = {
                        Icon(
                            imageVector = Icons.Default.Bluetooth,
                            contentDescription = null
                        )
                    },
                    title = stringResource(R.string.menu_ble_scanner_title),
                    subtitle = stringResource(R.string.menu_ble_scanner_subtitle),
                    onClick = {
                        navController.navigate(Routes.BLE_SCANNER) {
                            launchSingleTop = true
                        }
                    }
                )

                MenuEntryDivider()

                MenuEntryItem(
                    icon = {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = null
                        )
                    },
                    title = stringResource(R.string.engineer_band_lock_title),
                    subtitle = stringResource(R.string.menu_engineer_band_lock_subtitle),
                    onClick = {
                        navController.navigate(Routes.ENGINEER_BAND_LOCK) {
                            launchSingleTop = true
                        }
                    }
                )
            }

            Spacer(Modifier.height(24.dp + contentBottomPadding))
        }
    }
}

@Composable
private fun MenuSectionCard(
    title: String,
    subtitle: String,
    leadingIcon: (@Composable () -> Unit)?,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = BorderStroke(
            0.8.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (leadingIcon != null) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Box(
                            modifier = Modifier.size(42.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            leadingIcon()
                        }
                    }
                    Spacer(Modifier.size(12.dp))
                }

                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            content()
        }
    }
}

@Composable
private fun MenuEntryItem(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.secondaryContainer
        ) {
            Box(
                modifier = Modifier.size(42.dp),
                contentAlignment = Alignment.Center
            ) {
                icon()
            }
        }

        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun MenuEntryDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 4.dp),
        color = MaterialTheme.colorScheme.outlineVariant
    )
}

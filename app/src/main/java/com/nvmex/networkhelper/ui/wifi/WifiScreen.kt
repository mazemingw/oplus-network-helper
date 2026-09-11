


@file:Suppress("DEPRECATION")
package com.nvmex.networkhelper.ui.wifi

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.nvmex.networkhelper.hotspot.HotspotViewModel
import com.nvmex.networkhelper.hotspot.Phase
import com.nvmex.networkhelper.iperf.server.IperfServerService
import com.nvmex.networkhelper.ui.wifi.sections.PingGatewayPanel
import com.nvmex.networkhelper.ui.wifi.sections.QuickControlPanel
import com.nvmex.networkhelper.ui.wifi.sections.WifiChannelPanel
import com.nvmex.networkhelper.ui.wifi.sections.WifiInfoCard
import com.nvmex.networkhelper.ui.wifi.sections.WifiSignalPanel
import com.nvmex.networkhelper.ui.wifi.sections.WifiTuningPanel
import com.nvmex.networkhelper.viewmodel.wifi.WifiTuningViewModel
import com.nvmex.networkhelper.viewmodel.wifi.WifiViewModel


@Composable
fun WifiScreen(
    navController: NavHostController,
    contentBottomPadding: Dp = 0.dp,
    vm: WifiViewModel = hiltViewModel(),
    tuningVm: WifiTuningViewModel = hiltViewModel()
) {
    val ui by vm.ui.collectAsState()
    val tuningUi by tuningVm.ui.collectAsState()
    val listState = rememberLazyListState()
    val isScrolling by remember { derivedStateOf { listState.isScrollInProgress } }

    val routerIconRes = rememberDrawableIdByName("router")
    val phoneIconRes = rememberDrawableIdByName("phone")

    //热点状态
    val hotspotVm: HotspotViewModel = viewModel()
    val hotspotUi by hotspotVm.ui.collectAsState()
    val hotspotActive = hotspotUi.phase == Phase.RUNNING || hotspotUi.phase == Phase.STARTING
    DisposableEffect(hotspotVm) {
        hotspotVm.startObserving()
        onDispose { hotspotVm.stopObserving() }
    }
    //服务端状态
    val serverRunning by IperfServerService.running.collectAsState()


    DisposableEffect(isScrolling) {
        vm.startRefreshing(if (isScrolling) 2000L else 1000L)
        onDispose { vm.stopRefreshing() }
    }

    CompositionLocalProvider(LocalTextStyle provides MaterialTheme.typography.bodySmall) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
            state = listState,
            contentPadding = PaddingValues(
                start = 14.dp,
                end = 14.dp,
                top = 0.dp,
                bottom = contentBottomPadding
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                QuickControlPanel(
                    navController = navController,
                    hotspotRunning = hotspotActive,
                    serverRunning = serverRunning,
                    clientRunning = false,
                    modifier = Modifier.fillMaxWidth(),

                )
            }

            item {
                WifiInfoCard(
                    ui = ui,
                    routerIconRes = routerIconRes,
                    phoneIconRes = phoneIconRes,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            item { WifiSignalPanel(ui = ui, modifier = Modifier.fillMaxWidth()) }

            item {
                WifiChannelPanel(
                    ui = ui,
                    modifier = Modifier.fillMaxWidth(),
                    lightMode = isScrolling
                )
            }

            item {
                PingGatewayPanel(
                    modifier = Modifier.fillMaxWidth(),
                    wifiConnected = ui.isWifiConnected,
                    hiPerfEnabled = tuningUi.hiPerfEnabled,
                    lowLatencyEnabled = tuningUi.lowLatencyEnabled,
                    sampleIntervalMs = if (isScrolling) 2000L else 1000L
                )
            }

            item { WifiTuningPanel(modifier = Modifier.fillMaxWidth()) }

            item { Spacer(Modifier.height(12.dp)) }
        }
    }
}



@Composable
private fun rememberDrawableIdByName(name: String): Int {
    val ctx = LocalContext.current
    return remember(name) {
        ctx.resources.getIdentifier(name, "drawable", ctx.packageName)
    }
}






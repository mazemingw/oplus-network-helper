package com.nvmex.networkhelper.ui.network

import android.telephony.SubscriptionManager
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nvmex.networkhelper.R
import com.nvmex.networkhelper.model.network.NetworkPanelUiState
import com.nvmex.networkhelper.model.network.ModemQosUiState
import com.nvmex.networkhelper.ui.home.RealSignalIndicator
import com.nvmex.networkhelper.ui.settings.rememberAppSettings
import com.nvmex.networkhelper.util.network.SocVendorDetector
import com.nvmex.networkhelper.viewmodel.signal.NetworkPanelMultiSimViewModel
import kotlinx.coroutines.launch

@Composable
internal fun DefaultDataSimIcon(selected: Boolean) {
    Icon(
        painter = painterResource(R.drawable.sim_card),
        contentDescription = stringResource(R.string.default_data_sim),
        modifier = Modifier.size(14.dp),
        tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
fun NetworkPanelTabs(
    modifier: Modifier = Modifier,
    vm: NetworkPanelMultiSimViewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
    selectedIndex: Int,
    onSelectedIndexChange: (Int) -> Unit,
    onCurrentState: (NetworkPanelUiState) -> Unit,
    onShowBandHelp: () -> Unit,
    onLaunchEngineerMode: (() -> Unit)? = null,
    onOpenEngineerBandLock: ((Int?) -> Unit)? = null,
    onQuickLock: ((com.nvmex.networkhelper.util.qmi.QmiQuickLockRequest) -> Unit)? = null,
    onOpenSignalComparison: () -> Unit = {},
    onAllStates: ((Map<Int, NetworkPanelUiState>) -> Unit)? = null,
    overrideStates: Map<Int, NetworkPanelUiState>? = null,
    overrideSubIds: List<Int>? = null,
    playbackFrameIndex: Int? = null,
    playbackSessionKey: Int? = null,
    showTopBar: Boolean = true,
    modemQosState: ModemQosUiState = ModemQosUiState(),
    onRefreshModemQos: ((Int) -> Unit)? = null,
    onCleanupModemDiag: (() -> Unit)? = null
) {
    val sims by vm.sims.collectAsState()
    val frame by vm.frame.collectAsState()
    val cellTimelineBySubId by vm.cellTimelineBySubId.collectAsState()
    val radios by vm.qmiBySlot.collectAsState()
    val rxGroups by vm.qmiRxBySlot.collectAsState()
    val qmiErrors by vm.qmiErrors.collectAsState()
    val qmiLoading by vm.qmiLoading.collectAsState()
    val liveStates = frame.data
    val dds by vm.dataSubId.collectAsState()

    val states = overrideStates ?: liveStates
    val panelSubIds = overrideSubIds ?: sims.map { it.subscriptionId }
    if (panelSubIds.isEmpty()) return

    LaunchedEffect(states, panelSubIds) {
        onAllStates?.invoke(states.filterKeys { it in panelSubIds })
    }

    val safeIndex = selectedIndex.coerceIn(0, panelSubIds.lastIndex)
    val pagerState = rememberPagerState(
        initialPage = safeIndex,
        pageCount = { panelSubIds.size }
    )
    val scope = rememberCoroutineScope()
    val (settings, setSettings) = rememberAppSettings()
    val isQualcommPlatform = remember { SocVendorDetector.isQualcomm() }

    LaunchedEffect(safeIndex) {
        if (pagerState.currentPage != safeIndex) {
            pagerState.scrollToPage(safeIndex)
        }
    }

    LaunchedEffect(pagerState.currentPage) {
        val p = pagerState.currentPage
        if (p != safeIndex) onSelectedIndexChange(p)
    }

    val currentSubId = panelSubIds[pagerState.currentPage]
    val currentSim = sims.firstOrNull { it.subscriptionId == currentSubId }?.simSlotIndex?.plus(1)
    QmiRadioObservation(vm, currentSim, overrideStates == null && isQualcommPlatform,
        currentSubId, includeRx = isQualcommPlatform && settings.rxMeasurementsExpanded)
    val currentState = states[currentSubId] ?: NetworkPanelUiState(lastError = "加载中...", subId = currentSubId)
    LaunchedEffect(currentSubId, currentState.updatedAt) {
        onCurrentState(currentState)
    }

    var didAutoJump by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(panelSubIds, dds, overrideStates) {
        if (overrideStates != null) return@LaunchedEffect
        if (didAutoJump) return@LaunchedEffect
        if (dds == SubscriptionManager.INVALID_SUBSCRIPTION_ID) return@LaunchedEffect
        val dataIndex = panelSubIds.indexOf(dds)
        if (dataIndex >= 0) {
            didAutoJump = true
            if (dataIndex != pagerState.currentPage) {
                onSelectedIndexChange(dataIndex)
                pagerState.animateScrollToPage(dataIndex)
            }
        }
    }

    Column(modifier) {
        if (showTopBar) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                        shape = MaterialTheme.shapes.large
                    ),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.42f),
                tonalElevation = 0.dp,
                shadowElevation = 0.dp
            ) {
                ScrollableTabRow(
                    selectedTabIndex = pagerState.currentPage,
                    edgePadding = 6.dp,
                    containerColor = Color.Transparent,
                    contentColor = MaterialTheme.colorScheme.primary,
                    divider = {},
                    indicator = { tabPositions ->
                        TabRowDefaults.Indicator(
                            modifier = Modifier.tabIndicatorOffset(tabPositions[pagerState.currentPage]),
                            height = 3.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                ) {
                    panelSubIds.forEachIndexed { idx, subId ->
                        val simInfo = sims.firstOrNull { it.subscriptionId == subId }
                        val simTitle = simInfo?.let { "SIM${it.simSlotIndex + 1}" } ?: "SIM${idx + 1}"
                        val networkState = states[subId]
                        val generation = when {
                            networkState?.nrMode?.startsWith("NSA", ignoreCase = true) == true -> "5G"
                            networkState?.dataNetworkType.equals("NR", ignoreCase = true) -> "5G"
                            networkState?.dataNetworkType.equals("LTE", ignoreCase = true) -> "4G"
                            else -> null
                        }
                        val title = generation?.let { "$simTitle $it" } ?: simTitle
                        val isDataSim = subId == dds
                        Tab(
                            selected = idx == pagerState.currentPage,
                            onClick = {
                                onSelectedIndexChange(idx)
                                scope.launch { pagerState.animateScrollToPage(idx) }
                            },
                            text = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = title,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                    if (isDataSim) {
                                        DefaultDataSimIcon(idx == pagerState.currentPage)
                                    }
                                }
                            },
                            unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (onLaunchEngineerMode != null || onOpenEngineerBandLock != null || settings.enableRealSignalIcon) {
                Surface(
                    modifier = Modifier.border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                        shape = MaterialTheme.shapes.large
                    ),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.42f),
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp
                ) {
                    Row {
                        if (onLaunchEngineerMode != null) {
                            IconButton(onClick = onLaunchEngineerMode) {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = "一键启动工程模式",
                                    modifier = Modifier.size(22.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        if (onOpenEngineerBandLock != null) {
                            IconButton(onClick = { onOpenEngineerBandLock(currentSim) }) {
                                Icon(
                                    imageVector = Icons.Default.Lock,
                                    contentDescription = stringResource(R.string.engineer_band_lock_title),
                                    modifier = Modifier.size(22.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        if (settings.enableRealSignalIcon) {
                            IconButton(onClick = onOpenSignalComparison) {
                                RealSignalIndicator(
                                    state = currentState,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                    }
                }
            }
            }

            Spacer(Modifier.height(10.dp))
        }

        HorizontalPager(
            state = pagerState,
            verticalAlignment = Alignment.Top,
            modifier = Modifier.fillMaxWidth()
        ) { page ->
            val subId = panelSubIds[page]
            val s = states[subId] ?: NetworkPanelUiState(lastError = "加载中...", subId = subId)
            val slot = sims.firstOrNull { it.subscriptionId == subId }?.simSlotIndex?.takeIf { it in 0..1 }
            val now = System.currentTimeMillis()
            val freshRadio = slot?.let { radios[it] }?.takeIf { now - it.updatedAt in 0..12000 }
            NetworkPanelContent(
                modifier = Modifier.fillMaxWidth(),
                s = s,
                liveCellTimeline = cellTimelineBySubId[subId].orEmpty(),
                timelineNeighbors = if (overrideStates == null) com.nvmex.networkhelper.util.qmi.qmiTimelineNeighbors(freshRadio, s, slot, now) else emptyList(),
                lockSnapshot = if (overrideStates == null) freshRadio else null,
                quickLockSim = sims.firstOrNull { it.subscriptionId == subId }?.simSlotIndex?.takeIf { it in 0..1 }?.plus(1),
                onQuickLock = if (overrideStates == null && isQualcommPlatform) onQuickLock else null,
                onRecordCellTimeline = { point -> vm.recordCellTimeline(subId, point) },
                showNrBandwidth = subId == dds || overrideStates != null,
                onBandHelp = onShowBandHelp,
                playbackFrameIndex = playbackFrameIndex,
                playbackSessionKey = playbackSessionKey,
                showModemQos = overrideStates == null && isQualcommPlatform,
                modemQosSessions = modemQosState.valuesBySubId[subId],
                modemMmInfo = modemQosState.mmBySubId[subId],
                modemQosCollectorReady = modemQosState.collectorReady,
                modemQosRefreshing = modemQosState.refreshingSubId == subId,
                modemQosBusy = modemQosState.refreshing,
                isDefaultDataSim = subId == dds,
                modemQosError = modemQosState.errorBySubId[subId] ?: modemQosState.lastError,
                modemQosCleaning = modemQosState.cleaning,
                modemQosNotice = modemQosState.noticeBySubId[subId] ?: modemQosState.lastNotice,
                modemDiagCleanupOutput = modemQosState.cleanupOutput,
                onRefreshModemQos = onRefreshModemQos?.let { refresh -> { refresh(subId) } },
                onCleanupModemDiag = onCleanupModemDiag,
                showRxMeasurements = isQualcommPlatform,
                rxLive = overrideStates == null,
                rxActive = pagerState.settledPage == page,
                rxExpanded = settings.rxMeasurementsExpanded,
                rxSim = slot?.plus(1),
                rxGroups = if (freshRadio != null) rxGroups[slot].orEmpty() else emptyList(),
                rxError = qmiErrors[slot],
                rxLoading = qmiLoading[slot] == true,
                onRxRetry = vm::retryQmiMonitoring,
                onRxExpandedChange = { setSettings(settings.copy(rxMeasurementsExpanded = it)) }
            )
        }
    }

}

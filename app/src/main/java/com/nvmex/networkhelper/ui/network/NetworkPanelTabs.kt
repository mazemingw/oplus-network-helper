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
import com.nvmex.networkhelper.ui.home.RealSignalIndicator
import com.nvmex.networkhelper.ui.settings.rememberAppSettings
import com.nvmex.networkhelper.viewmodel.signal.NetworkPanelMultiSimViewModel
import kotlinx.coroutines.launch

@Composable
fun NetworkPanelTabs(
    modifier: Modifier = Modifier,
    vm: NetworkPanelMultiSimViewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
    selectedIndex: Int,
    onSelectedIndexChange: (Int) -> Unit,
    onCurrentState: (NetworkPanelUiState) -> Unit,
    onShowBandHelp: () -> Unit,
    onLaunchEngineerMode: (() -> Unit)? = null,
    onOpenEngineerBandLock: (() -> Unit)? = null,
    onOpenSignalComparison: () -> Unit = {},
    onAllStates: ((Map<Int, NetworkPanelUiState>) -> Unit)? = null,
    overrideStates: Map<Int, NetworkPanelUiState>? = null,
    overrideSubIds: List<Int>? = null,
    playbackFrameIndex: Int? = null,
    playbackSessionKey: Int? = null,
    showTopBar: Boolean = true
) {
    val sims by vm.sims.collectAsState()
    val frame by vm.frame.collectAsState()
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
    val (settings, _) = rememberAppSettings()

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
                        val title = simInfo?.let { "SIM${it.simSlotIndex + 1}" } ?: "SIM${idx + 1}"
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
                                        Icon(
                                            painter = painterResource(R.drawable.sim_card),
                                            contentDescription = "默认数据卡",
                                            modifier = Modifier.size(14.dp),
                                            tint = if (idx == pagerState.currentPage) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            }
                                        )
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
                            IconButton(onClick = onOpenEngineerBandLock) {
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
            modifier = Modifier.fillMaxWidth()
        ) { page ->
            val subId = panelSubIds[page]
            val s = states[subId] ?: NetworkPanelUiState(lastError = "加载中...", subId = subId)
            NetworkPanelContent(
                modifier = Modifier.fillMaxWidth(),
                s = s,
                showNrBandwidth = subId == dds || overrideStates != null,
                onBandHelp = onShowBandHelp,
                playbackFrameIndex = playbackFrameIndex,
                playbackSessionKey = playbackSessionKey
            )
        }
    }

}

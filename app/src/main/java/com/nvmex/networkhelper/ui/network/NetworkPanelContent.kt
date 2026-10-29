package com.nvmex.networkhelper.ui.network

import android.app.Activity
import com.nvmex.networkhelper.util.network.formatCellType
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.telephony.TelephonyDisplayInfo
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Card
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Velocity
import androidx.hilt.navigation.compose.hiltViewModel
import com.nvmex.networkhelper.R
import com.nvmex.networkhelper.model.menu.LteCellQueryBody
import com.nvmex.networkhelper.model.network.NetworkPanelUiState
import com.nvmex.networkhelper.model.network.CellTimelinePoint
import com.nvmex.networkhelper.model.network.ModemQosInfo
import com.nvmex.networkhelper.util.network.formatQosAmbrMbps
import com.nvmex.networkhelper.util.network.formatQosRateMbps
import com.nvmex.networkhelper.model.network.ModemQosSessions
import com.nvmex.networkhelper.model.network.ModemMmInfo
import com.nvmex.networkhelper.model.network.NrCaCarrier
import com.nvmex.networkhelper.model.network.QualityLevel
import com.nvmex.networkhelper.model.network.upsertCellTimeline
import com.nvmex.networkhelper.network.model.NrQueryReq
import com.nvmex.networkhelper.ui.base.PanelDivider
import com.nvmex.networkhelper.ui.home.BandColorRegistry
import com.nvmex.networkhelper.ui.map.AmapMapActivity
import com.nvmex.networkhelper.ui.network.sections.CaChip
import com.nvmex.networkhelper.ui.network.sections.FollowRecordDialog
import com.nvmex.networkhelper.ui.network.sections.KeyChipsRow
import com.nvmex.networkhelper.ui.network.sections.LteCellDetailDialog
import com.nvmex.networkhelper.ui.network.sections.LteServingCellRow
import com.nvmex.networkhelper.ui.network.sections.NetworkPanelHelpDialog
import com.nvmex.networkhelper.ui.network.sections.RxAntennaSection
import com.nvmex.networkhelper.ui.network.sections.NetworkSignalProgressBar
import com.nvmex.networkhelper.ui.network.sections.NetworkQualityBadge
import com.nvmex.networkhelper.ui.network.sections.localizedNetworkQualityText
import com.nvmex.networkhelper.ui.network.sections.NrCaDetailDialog
import com.nvmex.networkhelper.ui.network.sections.NrCaStableRow
import com.nvmex.networkhelper.ui.network.sections.isCarrierActivated
import com.nvmex.networkhelper.ui.network.sections.isServingCellUnknown
import com.nvmex.networkhelper.ui.settings.rememberAppSettings
import com.nvmex.networkhelper.util.home.DeveloperModePrefs
import com.nvmex.networkhelper.util.network.sections.NetworkQualityAssessment
import com.nvmex.networkhelper.util.network.sections.assessNetworkQuality
import com.nvmex.networkhelper.util.network.utils.formatCiWithSplit
import com.nvmex.networkhelper.viewmodel.menu.MenuCellQueryViewModel
import com.nvmex.networkhelper.viewmodel.menu.CellQueryUiState
import com.nvmex.networkhelper.viewmodel.signal.FollowRecordViewModel
import com.nvmex.networkhelper.xposed.translator.NrcaTranslator
import com.nvmex.networkhelper.util.network.NrcaBandwidthEncoding
import kotlinx.coroutines.delay
import java.util.Locale
@Composable
fun NetworkPanelContent(
    modifier: Modifier = Modifier,
    s: NetworkPanelUiState,
    liveCellTimeline: List<CellTimelinePoint>,
    timelineNeighbors: List<com.nvmex.networkhelper.util.qmi.QmiTimelineNeighbor> = emptyList(),
    lockSnapshot: com.nvmex.networkhelper.util.qmi.QmiRadioSnapshot? = null,
    onRecordCellTimeline: (CellTimelinePoint) -> Unit,
    quickLockSim: Int? = null,
    onQuickLock: ((com.nvmex.networkhelper.util.qmi.QmiQuickLockRequest) -> Unit)? = null,
    showNrBandwidth: Boolean = true,
    onBandHelp: (() -> Unit)? = null,
    playbackFrameIndex: Int? = null,
    playbackSessionKey: Int? = null,
    showModemQos: Boolean = false,
    modemQosSessions: ModemQosSessions? = null,
    modemMmInfo: ModemMmInfo? = null,
    modemQosCollectorReady: Boolean = false,
    modemQosRefreshing: Boolean = false,
    modemQosBusy: Boolean = false,
    isDefaultDataSim: Boolean = true,
    modemQosError: String? = null,
    onRefreshModemQos: (() -> Unit)? = null,
    modemQosCleaning: Boolean = false,
    modemQosNotice: String? = null,
    modemDiagCleanupOutput: String? = null,
    onCleanupModemDiag: (() -> Unit)? = null,
    showRxMeasurements: Boolean = false,
    rxLive: Boolean = true,
    rxActive: Boolean = true,
    rxExpanded: Boolean = false,
    rxSim: Int? = null,
    rxGroups: List<com.nvmex.networkhelper.util.qmi.QmiRxGroup> = emptyList(),
    rxError: String? = null,
    rxLoading: Boolean = false,
    onRxRetry: () -> Unit = {},
    onRxExpandedChange: (Boolean) -> Unit = {}
) {
    // =========================
    // =========================
    val isNsa = remember(s.nrMode) { s.nrMode.startsWith("NSA") }
    val modemQosSource = ModemQosInfo.Source.forNetwork(s.dataNetworkTypeRaw, s.nrMode, s.dataNetworkType)
    val modemData = modemQosSessions?.dataFor(modemQosSource)
    val modemIms = modemQosSessions?.imsFor(modemQosSource)
    var showLteAnchor by rememberSaveable(s.subId) { mutableStateOf(false) }

    val displayState = remember(s, isNsa, showLteAnchor) {
        if (isNsa && showLteAnchor) (s.nsaLteAnchor ?: s) else s
    }

    // =========================
    // =========================
    var hideSensitive by rememberSaveable { mutableStateOf(false) }

    val queryVm: MenuCellQueryViewModel = hiltViewModel(key = "cell_query_${s.subId}")
    val queryUi by queryVm.ui.collectAsState()
    var cellDialogQuery by remember { mutableStateOf<CellQueryUiState?>(null) }

    val tacInt = displayState.tac.trim().toIntOrNull()
    val earfcnInt = displayState.arfcn.trim().toIntOrNull()
    val eciLong = displayState.ci.trim().toLongOrNull()
    val pciInt = displayState.pci.trim().toIntOrNull()
    val cellTypeText = remember(displayState.cellType, earfcnInt) {
        formatCellType(displayState.cellType, earfcnInt)
    }

    val context = LocalContext.current
    val followVm: FollowRecordViewModel = hiltViewModel()
    val followUi by followVm.uiState.collectAsState()

    var showFollowRecordDialog by rememberSaveable { mutableStateOf(false) }
    var lastFollowWasLocal by rememberSaveable { mutableStateOf(true) }
    val pickPointLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val data = result.data ?: return@rememberLauncherForActivityResult

        val wgsLat = data.getDoubleExtra(AmapMapActivity.EXTRA_PICK_RESULT_WGS_LAT, Double.NaN)
        val wgsLon = data.getDoubleExtra(AmapMapActivity.EXTRA_PICK_RESULT_WGS_LON, Double.NaN)
        if (wgsLat.isNaN() || wgsLon.isNaN()) return@rememberLauncherForActivityResult

        val gcjLat = data.getDoubleExtra(AmapMapActivity.EXTRA_PICK_RESULT_GCJ_LAT, Double.NaN)
            .takeIf { !it.isNaN() }
        val gcjLon = data.getDoubleExtra(AmapMapActivity.EXTRA_PICK_RESULT_GCJ_LON, Double.NaN)
            .takeIf { !it.isNaN() }
        val poiName = data.getStringExtra(AmapMapActivity.EXTRA_PICK_RESULT_POI_NAME)
        val address = data.getStringExtra(AmapMapActivity.EXTRA_PICK_RESULT_ADDRESS)

        followVm.applyPickedLocation(
            wgsLat = wgsLat,
            wgsLon = wgsLon,
            gcjLat = gcjLat,
            gcjLon = gcjLon,
            poiName = poiName,
            address = address
        )
    }

    val devEnabled = remember {
        DeveloperModePrefs.isEnabled(context)
    }

    val hasBasicParams = when (displayState.cellType.uppercase()) {
        "LTE" -> displayState.tac != "-" &&
                displayState.pci != "-" &&
                displayState.ci != "-" &&
                displayState.arfcn != "-"
        "NR" -> displayState.tac != "-" &&
                displayState.pci != "-" &&
                displayState.ci != "-" &&
                displayState.arfcn != "-"
        else -> false
    }

    val servingCellUnknown = queryUi.isServingCellUnknown()

    val canShowFollowRecord =
            !hideSensitive &&
            hasBasicParams &&
            servingCellUnknown &&
            (displayState.cellType == "LTE" || displayState.cellType == "NR")

    LaunchedEffect(displayState.cellType, tacInt, earfcnInt, pciInt, eciLong, displayState.ci) {
        when (displayState.cellType) {
            "LTE" -> {
                if (tacInt == null || earfcnInt == null || eciLong == null) return@LaunchedEffect

                queryVm.queryLteDebounced(
                    LteCellQueryBody(
                        tac = tacInt,
                        earfcn = earfcnInt,
                        eci = eciLong,
                        pci = pciInt
                    ),
                    debounceMs = 600L
                )
            }

            "NR" -> {
                val gcellId = displayState.ci.trim().takeIf { it.isNotBlank() && it != "-" }

                if (gcellId == null && tacInt == null && earfcnInt == null && pciInt == null) {
                    queryVm.reset()
                    return@LaunchedEffect
                }

                queryVm.queryNrDebounced(
                    NrQueryReq(
                        gcellId = gcellId,
                        nrTac = tacInt,
                        nrArfcn = earfcnInt,
                        nrPci = pciInt
                    ),
                    debounceMs = 600L
                )
            }

            else -> {
                queryVm.reset()
            }
        }
    }

    val showNrBars = remember(displayState.dataNetworkType) { displayState.dataNetworkType == "NR" }

    val qualityAssessment = remember(displayState) {
        if (showNrBars) {
            assessNetworkQuality(
                rsrpString = displayState.ssRsrp,
                sinrString = displayState.ssSinr,
                rsrqString = displayState.ssRsrq,
                isNR = true
            )
        } else if (displayState.cellType == "LTE") {
            assessNetworkQuality(
                rsrpString = displayState.rsrp,
                sinrString = displayState.sinr,
                rsrqString = displayState.rsrq,
                rssiString = displayState.rssi,
                isNR = false
            )
        } else {
            NetworkQualityAssessment(
                level = QualityLevel.UNKNOWN,
                text = "unknown",
                score = 0
            )
        }
    }

    val networkQualityText = localizedNetworkQualityText(qualityAssessment)
    val qualityLevel = qualityAssessment.level

    val visibilityIcon = painterResource(id = R.drawable.visibility)
    val visibilityOffIcon = painterResource(id = R.drawable.visibility_off)

    var showHelpDialog by rememberSaveable { mutableStateOf(false) }
    var showSignalNetworkRaw by rememberSaveable(s.subId) { mutableStateOf(false) }
    var showLinkRateBytes by rememberSaveable(s.subId) { mutableStateOf(false) }
    var showModemQosDetails by rememberSaveable(s.subId) { mutableStateOf(false) }
    var showNonDataQosConfirm by rememberSaveable(s.subId) { mutableStateOf(false) }
    var playbackCellTimeline by remember(s.subId) { mutableStateOf(emptyList<CellTimelinePoint>()) }
    var lastTimelinePlaybackFrameIndex by remember(s.subId) { mutableStateOf(-1) }
    var lastTimelinePlaybackSession by remember(s.subId) { mutableStateOf(Int.MIN_VALUE) }
    val (settings, _) = rememberAppSettings()
    val followRecordPickTitle = stringResource(R.string.network_follow_record_pick_title)
    val copyNetworkField: (String, String) -> Unit = { label, value ->
        copyNetworkFieldToClipboard(context, label, value)
    }

    LaunchedEffect(
        s.subId,
        displayState.updatedAt,
        displayState.cellType,
        displayState.tac,
        displayState.pci,
        displayState.ci,
        displayState.band,
        displayState.arfcn,
        displayState.rsrp,
        displayState.rsrq,
        displayState.sinr,
        displayState.ssRsrp,
        displayState.ssRsrq,
        displayState.ssSinr,
        playbackFrameIndex,
        playbackSessionKey
    ) {
        val hasCell = displayState.tac != "-" || displayState.pci != "-" || displayState.ci != "-"
        if (!hasCell) return@LaunchedEffect
        val useNrSignal = displayState.dataNetworkType == "NR"
        val point = CellTimelinePoint(
            ts = displayState.updatedAt.takeIf { it > 0L } ?: System.currentTimeMillis(),
            cellType = displayState.cellType.ifBlank { "-" },
            tac = displayState.tac,
            pci = displayState.pci,
            ci = displayState.ci,
            band = displayState.band,
            rsrp = if (useNrSignal) displayState.ssRsrp else displayState.rsrp,
            rsrq = if (useNrSignal) displayState.ssRsrq else displayState.rsrq,
            sinr = if (useNrSignal) displayState.ssSinr else displayState.sinr,
            arfcn = displayState.arfcn, mcc = displayState.mcc, mnc = displayState.mnc
        )

        if (playbackFrameIndex != null) {
            val session = playbackSessionKey ?: 0
            if (lastTimelinePlaybackSession != session) {
                playbackCellTimeline = emptyList()
                lastTimelinePlaybackFrameIndex = -1
                lastTimelinePlaybackSession = session
            }

            when {
                lastTimelinePlaybackFrameIndex < 0 -> {
                    playbackCellTimeline = upsertCellTimeline(playbackCellTimeline, point)
                }
                playbackFrameIndex > lastTimelinePlaybackFrameIndex -> {
                    playbackCellTimeline = upsertCellTimeline(playbackCellTimeline, point)
                }
                playbackFrameIndex < lastTimelinePlaybackFrameIndex -> {
                    val removeCount = lastTimelinePlaybackFrameIndex - playbackFrameIndex
                    playbackCellTimeline = playbackCellTimeline.dropLast(
                        removeCount.coerceAtMost(playbackCellTimeline.size)
                    )
                }
                else -> Unit
            }
            lastTimelinePlaybackFrameIndex = playbackFrameIndex
            return@LaunchedEffect
        }

        if (lastTimelinePlaybackSession != Int.MIN_VALUE) {
            playbackCellTimeline = emptyList()
            lastTimelinePlaybackFrameIndex = -1
            lastTimelinePlaybackSession = Int.MIN_VALUE
        }

        onRecordCellTimeline(point)
    }

    val cellTimeline = if (playbackFrameIndex != null) playbackCellTimeline else liveCellTimeline

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // =========================
            // =========================
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(stringResource(R.string.network_panel_title), fontWeight = FontWeight.Bold)

                    IconButton(
                        onClick = { showHelpDialog = true },
                        modifier = Modifier.size(18.dp)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.help),
                            contentDescription = stringResource(R.string.network_field_help),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }


                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(s.dataNetworkType, fontWeight = FontWeight.SemiBold)

                    if (isNsa) {
                        TextButton(
                            onClick = { showLteAnchor = !showLteAnchor },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                        ) {
                            Text(
                                if (showLteAnchor) {
                                    stringResource(R.string.network_show_5g)
                                } else {
                                    stringResource(R.string.network_show_4g_anchor)
                                },
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    IconButton(
                        onClick = { hideSensitive = !hideSensitive },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            painter = if (hideSensitive) visibilityOffIcon else visibilityIcon,
                            contentDescription = "Toggle sensitive"
                        )
                    }
                }
            }


            KeyValueRow(stringResource(R.string.network_operator), s.operatorName)
            KeyValueRow("MCC/MNC", "${s.mcc} / ${s.mnc}")

            PanelDivider()

            val netAndMode = buildString {
                append(s.dataNetworkType.ifBlank { "-" })
                if (s.dataNetworkType == "NR" && s.nrMode.isNotBlank() && s.nrMode != "-") {
                    append("-")
                    append(s.nrMode)
                }
            }.ifBlank { "-" }

            KeyValueRow(stringResource(R.string.network_data_nr_mode), netAndMode)

            if (isNsa) {
                KeyValueRow(
                    stringResource(R.string.network_current_view),
                    if (showLteAnchor) {
                        stringResource(R.string.network_view_lte_anchor)
                    } else {
                        stringResource(R.string.network_view_nr)
                    }
                )
            }

            KeyValueRow(stringResource(R.string.network_cell_type), cellTypeText)

            if (displayState.duplex == "FDD") {
                KeyValueRow("DL FREQ ▼", displayState.freqDl)
                KeyValueRow("UL FREQ ▲", displayState.freqUl)
            } else {
                KeyValueRow("FREQ ▲▼", displayState.freqDl)
            }
            KeyValueRow(
                "LINK RATE ▲▼",
                formatLinkRate(displayState, showLinkRateBytes),
                onClick = { showLinkRateBytes = !showLinkRateBytes }
            )
            if (showModemQos) {
                val modemQosClick = { showModemQosDetails = true }
                KeyValueRow(
                    modemQosSource?.let { stringResource(R.string.network_qci_generation, it.generation) }
                        ?: stringResource(R.string.network_qci),
                    "",
                    onClick = modemQosClick,
                    valueContent = {
                        ModemQciValue(
                            ims = modemIms,
                            data = modemData,
                            currentSource = modemQosSource,
                            ready = modemQosCollectorReady,
                            refreshing = modemQosRefreshing
                        )
                    }
                )
                KeyValueRow(
                    stringResource(R.string.network_ims_ambr),
                    formatModemAmbr(modemIms, modemQosRefreshing),
                    onClick = modemQosClick
                )
                KeyValueRow(
                    stringResource(R.string.network_data_ambr),
                    formatModemAmbr(modemData, modemQosRefreshing),
                    onClick = modemQosClick
                )
            }

            PanelDivider()

            LteServingCellRow(
                s = displayState,
                hideSensitive = hideSensitive,
                queryUi = queryUi,
                devEnabled = devEnabled,
                onOpenDetail = { cellDialogQuery = queryUi },
                onFollowRecord = {
                    showFollowRecordDialog = true
                    followVm.clearResultMessage()
                    if (followUi.longitude == null || followUi.latitude == null) {
                        followVm.loadCurrentLocation()
                    }
                },
                label = stringResource(R.string.network_serving_cell)
            )

            LteCellDetailDialog(
                show = cellDialogQuery != null,
                queryUi = cellDialogQuery ?: CellQueryUiState.Idle,
                onDismiss = { cellDialogQuery = null },
                onLocalCellChanged = {
                    queryVm.reset()
                    if (displayState.cellType == "LTE" && tacInt != null && earfcnInt != null)
                        queryVm.queryLte(LteCellQueryBody(tac = tacInt, earfcn = earfcnInt, eci = eciLong, pci = pciInt))
                    else if (displayState.cellType == "NR") queryVm.queryNr(NrQueryReq(gcellId = displayState.ci,
                        nrTac = tacInt, nrArfcn = earfcnInt, nrPci = pciInt))
                },
                onCellChanged = {
                    when (displayState.cellType.uppercase()) {
                        "LTE" -> {
                            if (tacInt != null && earfcnInt != null && eciLong != null) {
                                queryVm.queryLteForce(
                                    LteCellQueryBody(
                                        tac = tacInt,
                                        earfcn = earfcnInt,
                                        eci = eciLong,
                                        pci = pciInt
                                    )
                                )
                            }
                        }

                        "NR" -> {
                            val gcellId = displayState.ci.trim().takeIf { it.isNotBlank() && it != "-" }
                            if (gcellId != null || tacInt != null || earfcnInt != null || pciInt != null) {
                                queryVm.queryNrForce(
                                    NrQueryReq(
                                        gcellId = gcellId,
                                        nrTac = tacInt,
                                        nrArfcn = earfcnInt,
                                        nrPci = pciInt
                                    )
                                )
                            }
                        }
                    }
                }
            )

            val cellIdLabel = when (displayState.cellType) {
                "NR" -> "NR-NCI"   // or NR-CI
                "LTE" -> "LTE-ECI"
                "WCDMA" -> "WCDMA-CID"
                "GSM" -> "GSM-CID"
                else -> "${displayState.cellType}-CellId"
            }

            if (!hideSensitive) {
                KeyValueRow(
                    "${displayState.cellType}-TAC",
                    displayState.tac,
                    onClick = { copyNetworkField("${displayState.cellType}-TAC", displayState.tac) }
                )
                KeyValueRow(
                    "${displayState.cellType}-PCI",
                    displayState.pci,
                    onClick = { copyNetworkField("${displayState.cellType}-PCI", displayState.pci) }
                )

                val cellIdValue = formatCiWithSplit(
                    displayState.cellType,
                    displayState.ci
                )

                KeyValueRow(
                    cellIdLabel,
                    cellIdValue,
                    onClick = { copyNetworkField(cellIdLabel, cellIdValue) }
                )
            } else {
                KeyValueRow("${displayState.cellType}-TAC", "***")
                KeyValueRow("${displayState.cellType}-PCI", "***")
                KeyValueRow(cellIdLabel, "***")
            }

            KeyValueRow(
                "${displayState.cellType}-ARFCN",
                displayState.arfcn,
                onClick = { copyNetworkField("${displayState.cellType}-ARFCN", displayState.arfcn) }
            )

            if (isNsa) {
                KeyValueRow(stringResource(R.string.network_nsa_dual_connectivity), s.anchorBandCombo)
            }

            val bandAndDuplex = buildString {
                val band = displayState.band.takeIf { it.isNotBlank() && it != "-" }
                val duplex = displayState.duplex.takeIf { it.isNotBlank() && it != "-" }

                val bandText = when {
                    band == null -> "-"
                    displayState.cellType == "NR" -> "N$band"
                    displayState.cellType == "LTE" -> "B$band"
                    else -> band
                }

                append(bandText)
                if (duplex != null) {
                    append(" / ")
                    append(duplex)
                }
            }.ifBlank { "-" }

            KeyValueRow(
                stringResource(R.string.network_band_duplex),
                bandAndDuplex,
                onClick = onBandHelp,
                valueColor = Color(0xFF448AFF)
            )

            val nrBandwidthEncoding = s.nrCaInfo?.bandwidthEncoding ?: NrcaBandwidthEncoding.LEGACY
            if (displayState.cellType == "LTE" || s.nrMode.startsWith("NSA", true)) {
                var showLteCa by rememberSaveable(s.subId) { mutableStateOf(false) }
                val carriers = s.lteCaInfo?.carriers.orEmpty()
                NrCaStableRow(label = "LTE-CA", chips = if (carriers.size < 2) emptyList() else carriers.map { c ->
                    CaChip(text = "${c.bandText ?: "B?"} ${c.dlBwText ?: "—"}", active = c.idx == 0 || c.scellState == 2)
                }, emptyText = stringResource(if (carriers.size == 1) R.string.network_single_carrier else R.string.state_no_data),
                    showLoading = false, onClick = { showLteCa = true })
                com.nvmex.networkhelper.ui.network.sections.LteCaDetailDialog(showLteCa, s.lteCaInfo) { showLteCa = false }
            }
            val nrDlBwText = remember(s.nrCaInfo, showNrBandwidth) {
                if (!showNrBandwidth) return@remember "-"

                val carriers = s.nrCaInfo?.carriers.orEmpty()
                if (carriers.isEmpty()) return@remember "-"

                fun mhzOf(c: NrCaCarrier): Int = NrcaTranslator.bandwidthMhzOrNull(c.dlBwRaw, nrBandwidthEncoding) ?: 0

                val configured = carriers.filter { it.dlStateRaw == 1 || it.dlStateRaw == 2 } // Configured
                val activated = carriers.filter { it.dlStateRaw == 2 } // Activated

                val totalMhz = configured.sumOf(::mhzOf)
                val activeMhz = activated.sumOf(::mhzOf)

                if (configured.size <= 1) {
                    if (totalMhz > 0) "${totalMhz}MHz" else "-"
                } else {
                    when {
                        totalMhz <= 0 && activeMhz <= 0 -> "-"
                        totalMhz > 0 && activeMhz > 0 -> "${totalMhz} / ${activeMhz}MHz"
                        totalMhz > 0 -> "${totalMhz} / -MHz"
                        else -> "- / ${activeMhz}MHz"
                    }
                }
            }

            if (s.dataNetworkType == "NR") {
                KeyValueRow(stringResource(R.string.network_total_active_bandwidth), nrDlBwText)
            }


            if (s.dataNetworkType == "NR") {
                var showCaDialog by rememberSaveable { mutableStateOf(false) }
                var nrCaEverReceived by rememberSaveable(s.subId) { mutableStateOf(false) }
                var nrCaLoadTimeout by rememberSaveable(s.subId, s.dataNetworkType) { mutableStateOf(false) }

                val nrCarriers = s.nrCaInfo?.carriers.orEmpty()

                LaunchedEffect(s.subId, s.dataNetworkType) {
                    if (s.dataNetworkType == "NR") {
                        nrCaLoadTimeout = false
                        delay(6_000L)
                        nrCaLoadTimeout = true
                    } else {
                        nrCaLoadTimeout = true
                    }
                }

                LaunchedEffect(s.subId, s.nrCaInfo?.updatedAt) {
                    if (nrCarriers.isNotEmpty()) {
                        nrCaEverReceived = true
                    }
                }

                val showNrCaLoading = s.dataNetworkType == "NR" &&
                        nrCarriers.isEmpty() &&
                        !nrCaEverReceived &&
                        !nrCaLoadTimeout

                val nrCaEmptyText = when {
                    nrCarriers.size == 1 && s.nrCaInfo?.frequencyOnly != true -> stringResource(R.string.network_single_carrier)
                    showNrCaLoading -> stringResource(R.string.state_loading)
                    else -> stringResource(R.string.state_no_data)
                }

                val caChips = remember(nrCarriers, nrBandwidthEncoding, s.nrCaInfo?.frequencyOnly, s.mcc, s.mnc) {
                    if (s.nrCaInfo?.frequencyOnly == true) {
                        nrCarriers.take(6).map { c -> CaChip(text =
                            (if (c.ccId == 0) "PCC · " else "") + com.nvmex.networkhelper.util.qmi.qmiNrCaBandLabel(c, s.mcc, s.mnc), active = c.ccId == 0) }
                    } else if (nrCarriers.size < 2) {
                        emptyList()
                    } else {
                        nrCarriers.take(6).map { c ->
                            CaChip(
                                text = NrcaTranslator.toChipText(c.bandRaw, c.dlBwRaw, c.ulBwRaw, nrBandwidthEncoding),
                                active = isCarrierActivated(c)
                            )
                        }
                    }
                }

                NrCaStableRow(
                    label = "NR-CA",
                    chips = caChips,
                    emptyText = nrCaEmptyText,
                    showLoading = showNrCaLoading,
                    onClick = {
                        if (!showNrCaLoading) {
                            showCaDialog = true
                        }
                    }
                )

                NrCaDetailDialog(
                    show = showCaDialog,
                    info = s.nrCaInfo,
                    onDismiss = { showCaDialog = false }
                )
                if (s.nrCaInfo?.frequencyOnly == true) Text(stringResource(R.string.qmi_nr_ca_frequency_note),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            PanelDivider()

            // =========================
            // =========================
            if (showNrBars) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showSignalNetworkRaw = !showSignalNetworkRaw },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        signalHeaderText(displayState, showSignalNetworkRaw),
                        fontWeight = FontWeight.Bold
                    )
                    NetworkQualityBadge(
                        qualityText = networkQualityText,
                        qualityLevel = qualityLevel
                    )
                }

                SignalBarRow(
                    label = "SS-RSRP",
                    valueText = displayState.ssRsrp,
                    fraction = fractionFromDbm(displayState.ssRsrp, min = -140.0, max = -70.0),
                )
                SignalBarRow(
                    label = "SS-RSRQ",
                    valueText = displayState.ssRsrq,
                    fraction = fractionFromDb(displayState.ssRsrq, min = -20.0, max = -3.0),
                )
                SignalBarRow(
                    label = "SS-SINR",
                    valueText = displayState.ssSinr,
                    fraction = fractionFromDb(displayState.ssSinr, min = -10.0, max = 30.0),
                )
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showSignalNetworkRaw = !showSignalNetworkRaw },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        signalHeaderText(displayState, showSignalNetworkRaw),
                        fontWeight = FontWeight.Bold
                    )
                    NetworkQualityBadge(
                        qualityText = networkQualityText,
                        qualityLevel = qualityLevel
                    )
                }

                SignalBarRow(
                    label = "RSSI",
                    valueText = displayState.rssi,
                    fraction = fractionFromDbm(displayState.rssi, min = -120.0, max = -50.0),
                )
                SignalBarRow(
                    label = "RSRP",
                    valueText = displayState.rsrp,
                    fraction = fractionFromDbm(displayState.rsrp, min = -140.0, max = -70.0),
                )
                SignalBarRow(
                    label = "RSRQ",
                    valueText = displayState.rsrq,
                    fraction = fractionFromDb(displayState.rsrq, min = -20.0, max = -3.0),
                )
                SignalBarRow(
                    label = "SINR",
                    valueText = displayState.sinr,
                    fraction = fractionFromDb(displayState.sinr, min = -10.0, max = 30.0),
                )
            }

            if (showRxMeasurements) RxAntennaSection(sim = rxSim, live = rxLive, active = rxActive,
                expanded = rxExpanded, onExpandedChange = onRxExpandedChange, groups = rxGroups,
                error = rxError, loading = rxLoading, onRetry = onRxRetry)

            if (!displayState.lastError.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.state_error_prefix, displayState.lastError),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            NetworkPanelHelpDialog(
                show = showHelpDialog,
                onDismiss = { showHelpDialog = false }
            )

            FollowRecordDialog(
                show = showFollowRecordDialog,
                panelState = displayState,
                uiState = followUi,
                onDismiss = {
                    showFollowRecordDialog = false
                    followVm.resetState()
                },
                onCellNameChange = { followVm.setCellName(it) },
                onReloadLocation = { followVm.loadCurrentLocation() },
                onPickFromMap = {
                    pickPointLauncher.launch(
                        AmapMapActivity.createIntentForPick(
                            context = context,
                            initialWgsLat = followUi.latitude,
                            initialWgsLon = followUi.longitude,
                            title = followRecordPickTitle
                        )
                    )
                },
                localOnly = !devEnabled,
                onSubmit = { lastFollowWasLocal = !devEnabled; followVm.submit(displayState, localOnly = !devEnabled) },
                onSaveLocal = { lastFollowWasLocal = true; followVm.submit(displayState, localOnly = true) },
                onSubmitSuccess = {
                    if (lastFollowWasLocal) queryVm.reset()
                    when (displayState.cellType.uppercase()) {
                        "LTE" -> {
                            if (tacInt != null && earfcnInt != null && eciLong != null) {
                                (if (lastFollowWasLocal) queryVm::queryLte else queryVm::queryLteForce)(
                                    LteCellQueryBody(
                                        tac = tacInt,
                                        earfcn = earfcnInt,
                                        eci = eciLong,
                                        pci = pciInt
                                    )
                                )
                            }
                        }

                        "NR" -> {
                            val gcellId = displayState.ci.trim().takeIf { it.isNotBlank() && it != "-" }
                            if (gcellId != null || tacInt != null || earfcnInt != null || pciInt != null) {
                                (if (lastFollowWasLocal) queryVm::queryNr else queryVm::queryNrForce)(
                                    NrQueryReq(
                                        gcellId = gcellId,
                                        nrTac = tacInt,
                                        nrArfcn = earfcnInt,
                                        nrPci = pciInt
                                    )
                                )
                            }
                        }
                    }
                },
                onVendorSelected = { followVm.setVendor(it) },
                onSiteTypeSelected = { followVm.setSiteType(it) },
                onApplyQuickSuffix = { followVm.applyQuickSuffix(displayState) }
            )

        }
        }

        if (settings.enableCellTimeline) {
            CellTimelinePanel(
                points = cellTimeline,
                neighbors = timelineNeighbors,
                locks = com.nvmex.networkhelper.util.qmi.qmiCurrentLocks(lockSnapshot, displayState.cellType,
                    quickLockSim?.minus(1), System.currentTimeMillis()),
                hideSensitive = hideSensitive,
                sim = quickLockSim,
                subId = s.subId,
                live = rxLive,
                onQuickLock = onQuickLock
            )
        }
    }


    if (showModemQosDetails) {
        ModemQosDetailsDialog(
            sessions = modemQosSessions,
            mm = modemMmInfo.takeIf { modemQosSource == ModemQosInfo.Source.NR_5GSM },
            currentSource = modemQosSource,
            error = modemQosError,
            refreshing = modemQosRefreshing,
            cleaning = modemQosCleaning,
            notice = modemQosNotice,
            cleanupOutput = modemDiagCleanupOutput,
            canRefresh = !modemQosBusy && !modemQosCleaning && onRefreshModemQos != null,
            canCleanup = !modemQosBusy && !modemQosCleaning && onCleanupModemDiag != null,
            onCleanup = { onCleanupModemDiag?.invoke() },
            onRefresh = {
                if (isDefaultDataSim) onRefreshModemQos?.invoke()
                else showNonDataQosConfirm = true
            },
            onDismiss = { showModemQosDetails = false }
        )
    }
    if (showNonDataQosConfirm) {
        AlertDialog(
            onDismissRequest = { showNonDataQosConfirm = false },
            text = { Text(stringResource(R.string.network_qos_non_data_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    showNonDataQosConfirm = false
                    onRefreshModemQos?.invoke()
                }) { Text(stringResource(R.string.action_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showNonDataQosConfirm = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}




@Composable
fun KeyValueRow(
    k: String,
    v: String,
    onClick: (() -> Unit)? = null,
    valueColor: Color? = null,
    valueContent: (@Composable () -> Unit)? = null
) {
    val labelWidth = 128.dp

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = k,
            modifier = Modifier.width(labelWidth),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        if (valueContent != null) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { valueContent() }
        } else Text(
            text = v,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = valueColor ?: LocalContentColor.current,
            textAlign = TextAlign.End,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ModemQosDetailsDialog(
    sessions: ModemQosSessions?,
    mm: ModemMmInfo?,
    currentSource: ModemQosInfo.Source?,
    error: String?,
    refreshing: Boolean,
    canRefresh: Boolean,
    cleaning: Boolean,
    notice: String?,
    cleanupOutput: String?,
    canCleanup: Boolean,
    onCleanup: () -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val title = stringResource(R.string.network_qos_details_title)
    val cleanupTitle = stringResource(R.string.network_qos_cleanup_result)
    val detailListState = rememberLazyListState()
    LaunchedEffect(currentSource) {
        when (currentSource) {
            ModemQosInfo.Source.LTE_ESM -> detailListState.scrollToItem(1)
            ModemQosInfo.Source.NR_5GSM -> detailListState.scrollToItem(2)
            null -> Unit
        }
    }
    fun sessionFields(info: ModemQosInfo?) = listOf(
        "DNN" to (info?.dnn?.uppercase(Locale.ROOT) ?: "-"),
        "LOG" to (info?.logCode?.let { "0x${it.toString(16).uppercase(Locale.ROOT)}" } ?: if (info != null) "AT" else "-"),
        "QCI / 5QI" to (info?.qci?.toString() ?: "-"),
        "5QI / QFI" to "${info?.fiveQi ?: "-"} / ${info?.qfi ?: "-"}",
        "AMBR DL" to (info?.downlinkAmbrKbps?.let(::formatQosRateMbps) ?: "-"),
        "AMBR UL" to (info?.uplinkAmbrKbps?.let(::formatQosRateMbps) ?: "-")
    )
    val data = sessions?.dataFor(currentSource)
    val ims = sessions?.imsFor(currentSource)
    val status = stringResource(when {
        cleaning -> R.string.network_qos_cleaning
        error != null -> R.string.network_qos_status_failed
        refreshing && data != null && ims != null -> R.string.network_qos_status_received
        refreshing -> R.string.network_qos_reading
        data != null && ims != null -> R.string.network_qos_status_success
        data != null -> R.string.network_qos_status_data_only
        ims != null -> R.string.network_qos_status_ims_only
        else -> R.string.network_qos_status_unread
    })
    fun sessionBlock(label: String, info: ModemQosInfo?): String = buildString {
        appendLine("$label${if (info != null && info.source == currentSource && info.sessionKind == ModemQosInfo.SessionKind.DATA) " *" else ""}")
        appendLine("DNN: ${info?.dnn?.uppercase(Locale.ROOT) ?: "-"}")
        appendLine("LOG: ${info?.logCode?.let { "0x${it.toString(16).uppercase(Locale.ROOT)}" } ?: if (info != null) "AT" else "-"}")
        appendLine("QCI: ${info?.qci ?: "-"}")
        appendLine("5QI / QFI: ${info?.fiveQi ?: "-"} / ${info?.qfi ?: "-"}")
        appendLine("AMBR DL: ${info?.downlinkAmbrKbps?.let(::formatQosRateMbps) ?: "-"}")
        append("AMBR UL: ${info?.uplinkAmbrKbps?.let(::formatQosRateMbps) ?: "-"}")
    }

    val detail = buildString {
        appendLine("MM LOG: ${mm?.let { "0x${it.logCode.toString(16).uppercase(Locale.ROOT)}" } ?: "-"}")
        appendLine("MM STATE: ${mm?.mmState ?: "-"}")
        appendLine("MM SUB STATE: ${mm?.mmSubState ?: "-"}")
        appendLine("5G TMSI: ${mm?.fiveGTmsi ?: "-"}")
        appendLine()
        appendLine(sessionBlock("DATA (4G)", sessions?.lteData))
        appendLine()
        append(sessionBlock("DATA (5G)", sessions?.nrData))
        appendLine()
        appendLine()
        appendLine(sessionBlock("IMS (4G)", sessions?.lteIms))
        appendLine()
        append(sessionBlock("IMS (5G)", sessions?.nrIms))
    }
    val copyText = buildString {
        appendLine(title)
        appendLine(status)
        appendLine()
        append(detail)
        error?.let { appendLine(); appendLine(); append(it) }
        notice?.let { appendLine(); appendLine(); append(it) }
        cleanupOutput?.let {
            appendLine()
            appendLine()
            appendLine(cleanupTitle)
            append(it)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp),
                    color = if (error != null) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(status, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold)
                }
                if (refreshing || cleaning) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                LazyColumn(
                    modifier = Modifier.height(360.dp),
                    state = detailListState,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item { QosPropertyTable("5G MM", listOf(
                        "MM LOG" to (mm?.let { "0x${it.logCode.toString(16).uppercase(Locale.ROOT)}" } ?: "-"),
                        "MM STATE" to (mm?.mmState?.toString() ?: "-"),
                        "MM SUB STATE" to (mm?.mmSubState?.toString() ?: "-"),
                        "5G TMSI" to (mm?.fiveGTmsi ?: "-")
                    )) }
                    item { QosPropertyTable("4G · DATA", sessionFields(sessions?.lteData), Icons.Default.SwapVert) }
                    item { QosPropertyTable("5G · DATA", sessionFields(sessions?.nrData), Icons.Default.SwapVert) }
                    item { QosPropertyTable("IMS · 4G", sessionFields(sessions?.lteIms), Icons.Default.Call) }
                    item { QosPropertyTable("IMS · 5G", sessionFields(sessions?.nrIms), Icons.Default.Call) }
                    error?.let { item { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) } }
                    notice?.let { item { Text(it, style = MaterialTheme.typography.bodySmall) } }
                    cleanupOutput?.let { item {
                        HorizontalDivider()
                        Text(cleanupTitle)
                        Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    } }
                }
            }
        },
        confirmButton = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(onClick = { copyNetworkFieldToClipboard(context, title, copyText) }) {
                        Text(stringResource(R.string.network_qos_copy))
                    }
                    TextButton(onClick = onCleanup, enabled = canCleanup) {
                        Text(stringResource(if (cleaning) R.string.network_qos_cleaning else R.string.network_qos_cleanup_diag))
                    }
                    TextButton(onClick = onRefresh, enabled = canRefresh) {
                        Text(
                            if (refreshing) stringResource(R.string.network_qos_reading)
                            else stringResource(R.string.network_qos_refresh_sessions)
                        )
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_close))
                }
            }
        }
    )
}

@Composable
private fun QosPropertyTable(title: String, fields: List<Pair<String, String>>, icon: ImageVector? = null) {
    Column(
        modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
            .clip(RoundedCornerShape(8.dp))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            icon?.let {
                Icon(it, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        }
        fields.forEachIndexed { index, (attribute, value) ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            Row(modifier = Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(attribute, modifier = Modifier.weight(0.4f), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value, modifier = Modifier.weight(0.6f), style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.End, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

private fun copyNetworkFieldToClipboard(context: Context, label: String, value: String) {
    val copiedValue = value.trim()
    if (copiedValue.isBlank() || copiedValue == "-" || copiedValue == "***") return

    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(label, copiedValue))
    Toast.makeText(
        context,
        context.getString(R.string.network_field_copied, label),
        Toast.LENGTH_SHORT
    ).show()
}

private fun signalHeaderText(s: NetworkPanelUiState, showRaw: Boolean): String {
    if (showRaw) {
        val networkType = s.dataNetworkTypeRaw?.toString() ?: "-"
        val networkName = s.dataNetworkTypeRawName.takeIf { it.isNotBlank() && it != "-" }
        val overrideType = s.overrideNetworkTypeRaw?.toString() ?: "-"
        val overrideName = s.overrideNetworkTypeName.takeIf { it.isNotBlank() && it != "-" }
        return buildString {
            append("RAW NT=")
            append(networkType)
            if (networkName != null) {
                append(" ")
                append(networkName)
            }
            append(" OVR=")
            append(overrideType)
            if (overrideName != null) {
                append(" ")
                append(overrideName)
            }
        }
    }

    return when (s.dataNetworkType.uppercase(Locale.US)) {
        "NR" -> if (isNrAdvancedOverride(s.overrideNetworkTypeRaw)) "NR 5G Advanced" else "NR 5G"
        "LTE" -> if (isLtePlusOverride(s.overrideNetworkTypeRaw)) "LTE 4G+" else "LTE 4G"
        else -> s.dataNetworkType.takeIf { it.isNotBlank() } ?: "-"
    }
}

@Composable
private fun CellTimelinePanel(
    points: List<CellTimelinePoint>,
    neighbors: List<com.nvmex.networkhelper.util.qmi.QmiTimelineNeighbor>,
    locks: com.nvmex.networkhelper.util.qmi.QmiCurrentLocks,
    hideSensitive: Boolean,
    sim: Int?,
    subId: Int,
    live: Boolean,
    onQuickLock: ((com.nvmex.networkhelper.util.qmi.QmiQuickLockRequest) -> Unit)?
) {
    var selected by remember(sim, subId, live) { mutableStateOf<CellTimelinePoint?>(null) }
    var expanded by rememberSaveable(sim, subId, live) { mutableStateOf(false) }
    val neighborListState = rememberLazyListState()
    val neighborInteraction = remember(sim, subId, live) { MutableInteractionSource() }
    val neighborPressed by neighborInteraction.collectIsPressedAsState()
    val neighborInteracting = neighborPressed || neighborListState.isScrollInProgress
    val neighborHold = remember(sim, subId, live) { com.nvmex.networkhelper.util.qmi.QmiNeighborDisplayHold() }
    var shownNeighbors by remember(sim, subId, live) { mutableStateOf(emptyList<com.nvmex.networkhelper.util.qmi.QmiTimelineNeighbor>()) }
    LaunchedEffect(neighborHold, neighbors, expanded, neighborInteracting) {
        val now = android.os.SystemClock.elapsedRealtime()
        shownNeighbors = neighborHold.update(neighbors, now, neighborInteracting)
        val remaining = if (neighborInteracting) 0 else neighborHold.remainingMs(now)
        if (remaining > 0) {
            kotlinx.coroutines.delay(remaining)
            shownNeighbors = neighborHold.update(neighbors, android.os.SystemClock.elapsedRealtime(), neighborPressed || neighborListState.isScrollInProgress)
        }
    }
    selected?.let { point ->
        val cellRequest = remember(point, sim, subId) { runCatching {
            com.nvmex.networkhelper.util.qmi.qmiQuickLockRequest(sim ?: -1, subId, point,
                com.nvmex.networkhelper.util.qmi.QmiQuickLockKind.CELL)
        }.getOrNull() }
        val bandRequest = remember(point, sim, subId) { runCatching {
            com.nvmex.networkhelper.util.qmi.qmiQuickLockRequest(sim ?: -1, subId, point,
                com.nvmex.networkhelper.util.qmi.QmiQuickLockKind.BAND)
        }.getOrNull() }
        AlertDialog(onDismissRequest = { selected = null },
            title = { Text(stringResource(R.string.qmi_quick_title, sim ?: 0)) },
            text = { Text(stringResource(R.string.qmi_quick_confirm,
                "${point.cellType} ${timelineBandLabel(point)} · " +
                    if (hideSensitive) "***" else "${point.arfcn} · PCI ${point.pci}") +
                if (cellRequest == null) "\n${stringResource(R.string.qmi_quick_cell_missing)}" else "") },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = bandRequest != null, shape = RoundedCornerShape(4.dp), onClick = {
                        selected = null; bandRequest?.let { onQuickLock?.invoke(it) }
                    }) { Text(stringResource(R.string.qmi_quick_band)) }
                    Button(enabled = cellRequest != null, shape = RoundedCornerShape(4.dp), onClick = {
                        selected = null; cellRequest?.let { onQuickLock?.invoke(it) }
                    }) { Text(stringResource(R.string.qmi_quick_cell)) }
                }
            }, dismissButton = { TextButton(onClick = { selected = null }, shape = RoundedCornerShape(4.dp)) {
                Text(stringResource(R.string.action_cancel))
            } })
    }
    val visible = remember(points, shownNeighbors.isNotEmpty()) { if (shownNeighbors.isEmpty()) points.takeLast(20) else emptyList() }
    val listState = rememberLazyListState()
    val containListScroll = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset = if (source == NestedScrollSource.UserInput) {
                Offset(0f, available.y)
            } else Offset.Zero

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
                Velocity(0f, available.y)
        }
    }

    LaunchedEffect(expanded, visible.size, visible.lastOrNull()?.ts) {
        if (expanded && visible.isNotEmpty()) listState.scrollToItem(visible.lastIndex)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(stringResource(if (shownNeighbors.isEmpty()) R.string.network_cell_timeline else R.string.qmi_timeline_neighbors), fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                if (live && sim != null) {
                    fun status(value: Boolean?) = when (value) {
                        true -> R.string.qmi_summary_locked; false -> R.string.qmi_summary_unlocked; null -> R.string.qmi_summary_unknown
                    }
                    Text(stringResource(R.string.qmi_current_lock_summary, stringResource(status(locks.cell)), stringResource(status(locks.rat))),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1)
                }
                if (visible.size > 5 || shownNeighbors.size > 5) {
                    IconButton(onClick = {
                        neighborHold.retainForInteraction(android.os.SystemClock.elapsedRealtime())
                        expanded = !expanded
                    }, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = stringResource(
                                if (expanded) R.string.action_collapse else R.string.action_expand
                            )
                        )
                    }
                }
                }
            }

            if (visible.isEmpty() && shownNeighbors.isEmpty()) {
                TimelineHeader()
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) { repeat(5) { EmptyTimelineRow() } }
            } else if (visible.isNotEmpty()) {
                TimelineHeader()
                if (expanded && visible.size > 5) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height((visible.size.coerceAtMost(8) * 42).dp)
                            .nestedScroll(containListScroll),
                        state = listState,
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                        contentPadding = PaddingValues(bottom = 2.dp)
                    ) {
                        items(
                            count = visible.size,
                            key = { index -> timelinePointKey(visible[index]) }
                        ) { index ->
                            TimelineRow(
                                point = visible[index],
                                hideSensitive = hideSensitive,
                                isCurrent = index == visible.lastIndex,
                                onClick = if (onQuickLock != null && sim != null) ({ selected = visible[index] }) else null
                            )
                        }
                    }
                } else {
                    val collapsed = visible.takeLast(5)
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        collapsed.forEach { point ->
                            TimelineRow(
                                point = point,
                                hideSensitive = hideSensitive,
                                isCurrent = point.sameCell(visible.last()),
                                onClick = if (onQuickLock != null && sim != null) ({ selected = point }) else null
                            )
                        }
                        repeat((5 - collapsed.size).coerceAtLeast(0)) { EmptyTimelineRow() }
                    }
                }
            }
            if (shownNeighbors.isNotEmpty()) {
                TimelineHeader()
                val neighborRow: @Composable (com.nvmex.networkhelper.util.qmi.QmiTimelineNeighbor) -> Unit = { neighbor ->
                    TimelineRow(neighbor.point, hideSensitive, false,
                        onClick = if (onQuickLock != null && sim != null) ({ selected = neighbor.point }) else null,
                        sourceLabel = stringResource(R.string.qmi_neighbor_cell), interactionSource = neighborInteraction)
                }
                if (expanded && shownNeighbors.size > 5) {
                    LazyColumn(Modifier.fillMaxWidth().height((shownNeighbors.size.coerceAtMost(6) * 42).dp)
                        .nestedScroll(containListScroll), state = neighborListState, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        items(count = shownNeighbors.size, key = { index -> shownNeighbors[index].point.let { "${it.cellType}:${it.arfcn}:${it.pci}" } }) {
                            neighborRow(shownNeighbors[it])
                        }
                    }
                } else Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    shownNeighbors.take(5).forEach { neighborRow(it) }
                    repeat((5 - shownNeighbors.size).coerceAtLeast(0)) { EmptyTimelineRow() }
                }
            }
        }
    }
}

private fun timelinePointKey(point: CellTimelinePoint): String =
    "${point.cellType}-${point.tac}-${point.pci}-${point.ci}"

@Composable
private fun EmptyTimelineRow() {
    Box(Modifier.fillMaxWidth().height(40.dp).border(1.dp,
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f), RoundedCornerShape(9.dp)))
}

@Composable
private fun TimelineRow(
    point: CellTimelinePoint,
    hideSensitive: Boolean,
    isCurrent: Boolean,
    onClick: (() -> Unit)? = null,
    sourceLabel: String? = null,
    interactionSource: MutableInteractionSource? = null
) {
    val rowInteraction = interactionSource ?: remember { MutableInteractionSource() }
    val tac = if (hideSensitive) "***" else point.tac
    val pci = if (hideSensitive) "***" else point.pci
    val channel = if (hideSensitive) "***" else point.arfcn
    val rsrpDbm = com.nvmex.networkhelper.util.qmi.qmiRsrp(point.rsrp)?.toInt()
    val rsrp = rsrpDbm?.toString() ?: "—"
    val band = timelineBandLabel(point)
    val accent = timelineCellColor(point)
    val shape = RoundedCornerShape(9.dp)
    val borderColor = accent.copy(alpha = if (isCurrent) 0.95f else 0.18f)
    val backgroundColor = accent.copy(alpha = if (isCurrent) 0.18f else 0.035f)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(shape)
            .then(if (onClick != null) Modifier.clickable(interactionSource = rowInteraction,
                indication = LocalIndication.current, onClick = onClick) else Modifier)
            .background(backgroundColor)
            .border(if (isCurrent) 2.dp else 1.dp, borderColor, shape)
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.CellTower,
            contentDescription = null,
            tint = accent.copy(alpha = if (isCurrent) 1f else 0.55f),
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(6.dp))
        TimelineValue(tac, Modifier.width(48.dp))
        TimelineValue(pci, Modifier.width(36.dp))
        TimelineValue(channel, Modifier.weight(1f))
        TimelineValue(
            band,
            Modifier.width(36.dp),
            accent.copy(alpha = if (isCurrent) 1f else 0.72f)
        )
        TimelineValue(rsrp, Modifier.width(45.dp),
            Color(com.nvmex.networkhelper.util.map.DriveStyle.colorByRsrp(rsrpDbm)), FontWeight.Bold)
        Box(modifier = Modifier.width(39.dp), contentAlignment = Alignment.CenterEnd) {
            if (isCurrent) {
                Surface(
                    color = accent,
                    shape = RoundedCornerShape(999.dp),
                    shadowElevation = 3.dp
                ) {
                    Text(
                        text = stringResource(R.string.network_cell_timeline_current),
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (accent.luminance() > 0.48f) Color.Black else Color.White,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }
            } else if (sourceLabel != null) {
                Text(sourceLabel, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}

@Composable
private fun TimelineHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Spacer(Modifier.width(26.dp))
        TimelineHeaderText("TAC", Modifier.width(48.dp))
        TimelineHeaderText("PCI", Modifier.width(36.dp))
        TimelineHeaderText(stringResource(R.string.network_cell_timeline_channel), Modifier.weight(1f))
        TimelineHeaderText(stringResource(R.string.network_cell_timeline_band), Modifier.width(36.dp))
        TimelineHeaderText("RSRP", Modifier.width(45.dp))
        Spacer(Modifier.width(39.dp))
    }
}

@Composable
private fun TimelineHeaderText(text: String, modifier: Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun TimelineValue(value: String, modifier: Modifier, color: Color? = null, fontWeight: FontWeight = FontWeight.SemiBold) {
    Text(
        value,
        modifier,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = fontWeight,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        color = color ?: LocalContentColor.current
    )
}

private fun timelineCellColor(point: CellTimelinePoint): Color =
    BandColorRegistry.colorOf(timelineBandLabel(point))

private fun timelineBandLabel(point: CellTimelinePoint): String {
    val raw = point.band.trim().uppercase(Locale.ROOT)
    if (raw.isBlank() || raw == "-") return "-"
    if (raw.startsWith("B") || raw.startsWith("N")) return raw
    val prefix = if (point.cellType.equals("LTE", ignoreCase = true)) "B" else "N"
    return prefix + raw
}

private fun isNrAdvancedOverride(overrideType: Int?): Boolean {
    if (overrideType == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
    return overrideType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED ||
            overrideType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA_MMWAVE
}

private fun isLtePlusOverride(overrideType: Int?): Boolean {
    if (overrideType == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
    return overrideType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_CA ||
            overrideType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_ADVANCED_PRO
}

private fun formatLinkRate(s: NetworkPanelUiState, asBytesPerSecond: Boolean): String {
    val down = s.linkDownstreamKbps?.let {
        if (asBytesPerSecond) formatKbpsAsBytesPerSecondValue(it) else formatKbpsValue(it)
    }
    val up = s.linkUpstreamKbps?.let {
        if (asBytesPerSecond) formatKbpsAsBytesPerSecondValue(it) else formatKbpsValue(it)
    }
    val unit = listOfNotNull(down?.second, up?.second).distinct().singleOrNull()

    return when {
        down != null && up != null && unit != null -> "${down.first} / ${up.first} $unit"
        down != null && up != null -> "${down.first} ${down.second} / ${up.first} ${up.second}"
        down != null && unit != null -> "${down.first} / - $unit"
        up != null && unit != null -> "- / ${up.first} $unit"
        else -> "-"
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun ModemQciValue(
    ims: ModemQosInfo?, data: ModemQosInfo?, currentSource: ModemQosInfo.Source?,
    ready: Boolean, refreshing: Boolean
) {
    if (refreshing || (ims == null && data == null)) {
        Text(
            when {
                refreshing -> stringResource(R.string.network_qos_reading)
                ready -> stringResource(R.string.network_qos_tap_to_read)
                else -> "-"
            }, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold
        )
        return
    }
    fun qosClass(info: ModemQosInfo?): String = info?.qci?.toString()
        ?: info?.fiveQi?.let { "5QI $it" } ?: "-"
    val imsGeneration = if (ims != null && ims.source != currentSource) " (${ims.source.generation})" else ""
    val dataName = data?.dnn?.substringBefore('.')?.uppercase(Locale.ROOT) ?: "DATA"
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Icon(Icons.Default.Call, contentDescription = "IMS", modifier = Modifier.size(15.dp))
            Text("IMS:${qosClass(ims)}$imsGeneration", style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Icon(Icons.Default.SwapVert, contentDescription = "DATA", modifier = Modifier.size(15.dp))
            Text("$dataName:${qosClass(data)}", style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun formatModemAmbr(info: ModemQosInfo?, refreshing: Boolean): String {
    if (refreshing) return stringResource(R.string.network_qos_reading)
    info ?: return "-"
    return formatQosAmbrMbps(info.uplinkAmbrKbps, info.downlinkAmbrKbps)
}

private fun formatKbpsAsBytesPerSecondValue(kbps: Int): Pair<String, String> {
    val bytesPerSecondKb = kbps / 8.0
    return when {
        bytesPerSecondKb >= 1_000 -> String.format(Locale.US, "%.1f", bytesPerSecondKb / 1_000.0) to "MB/s"
        else -> String.format(Locale.US, "%.1f", bytesPerSecondKb) to "KB/s"
    }
}

private fun formatKbpsValue(kbps: Int): Pair<String, String> {
    return when {
        kbps >= 1_000_000 -> String.format(Locale.US, "%.2f", kbps / 1_000_000.0) to "Gbps"
        kbps >= 1_000 -> String.format(Locale.US, "%.1f", kbps / 1_000.0) to "Mbps"
        else -> kbps.toString() to "Kbps"
    }
}

/**
 */
@Composable
private fun SignalBarRow(
    label: String,
    valueText: String,
    fraction: Float
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            modifier = Modifier.width(92.dp),
            style = MaterialTheme.typography.bodyMedium
        )

        NetworkSignalProgressBar(
            fraction = fraction.takeUnless { valueText == "-" || valueText.isBlank() },
            modifier = Modifier.weight(1f)
        )

        Spacer(Modifier.width(10.dp))

        Text(
            text = valueText,
            modifier = Modifier.widthIn(min = 70.dp),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}


@Composable
private fun UpdatedAtRow(updatedAt: Long) {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(updatedAt) {
        while (true) {
            delay(1000)
            now = System.currentTimeMillis()
        }
    }

//    val deltaSec = ((now - updatedAt).coerceAtLeast(0L) / 1000L)
//
//    Text(
//        style = MaterialTheme.typography.labelSmall,
//        color = MaterialTheme.colorScheme.onSurfaceVariant
//    )
}



private fun parseFirstNumber(s: String): Double? {
    val m = Regex("""-?\d+(\.\d+)?""").find(s) ?: return null
    return m.value.toDoubleOrNull()
}

private fun fractionFromDbm(text: String, min: Double, max: Double): Float {
    val v = parseFirstNumber(text) ?: return 0f
    return ((v - min) / (max - min)).toFloat().coerceIn(0f, 1f)
}

private fun fractionFromDb(text: String, min: Double, max: Double): Float {
    val v = parseFirstNumber(text) ?: return 0f
    return ((v - min) / (max - min)).toFloat().coerceIn(0f, 1f)
}

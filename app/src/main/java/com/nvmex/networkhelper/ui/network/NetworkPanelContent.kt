package com.nvmex.networkhelper.ui.network

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.telephony.TelephonyDisplayInfo
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.nvmex.networkhelper.R
import com.nvmex.networkhelper.model.menu.LteCellQueryBody
import com.nvmex.networkhelper.model.network.NetworkPanelUiState
import com.nvmex.networkhelper.model.network.NrCaCarrier
import com.nvmex.networkhelper.model.network.QualityLevel
import com.nvmex.networkhelper.network.model.NrQueryReq
import com.nvmex.networkhelper.ui.base.PanelDivider
import com.nvmex.networkhelper.ui.map.AmapMapActivity
import com.nvmex.networkhelper.ui.network.sections.CaChip
import com.nvmex.networkhelper.ui.network.sections.FollowRecordDialog
import com.nvmex.networkhelper.ui.network.sections.KeyChipsRow
import com.nvmex.networkhelper.ui.network.sections.LteCellDetailDialog
import com.nvmex.networkhelper.ui.network.sections.LteServingCellRow
import com.nvmex.networkhelper.ui.network.sections.NetworkPanelHelpDialog
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
import com.nvmex.networkhelper.viewmodel.signal.FollowRecordViewModel
import com.nvmex.networkhelper.xposed.translator.NrcaTranslator
import kotlinx.coroutines.delay
import java.util.Locale
@Composable
fun NetworkPanelContent(
    modifier: Modifier = Modifier,
    s: NetworkPanelUiState,
    showNrBandwidth: Boolean = true,
    onBandHelp: (() -> Unit)? = null,
    playbackFrameIndex: Int? = null,
    playbackSessionKey: Int? = null
) {
    // =========================
    // =========================
    val isNsa = remember(s.nrMode) { s.nrMode.startsWith("NSA") }
    var showLteAnchor by rememberSaveable(s.subId) { mutableStateOf(false) }

    val displayState = remember(s, isNsa, showLteAnchor) {
        if (isNsa && showLteAnchor) (s.nsaLteAnchor ?: s) else s
    }

    // =========================
    // =========================
    var hideSensitive by rememberSaveable { mutableStateOf(false) }

    val queryVm: MenuCellQueryViewModel = hiltViewModel(key = "cell_query_${s.subId}")
    val queryUi by queryVm.ui.collectAsState()
    var showCellDialog by rememberSaveable { mutableStateOf(false) }

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

    val canShowFollowRecord = devEnabled &&
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
    var cellTimeline by remember(s.subId) { mutableStateOf(emptyList<CellTimelinePoint>()) }
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
            rsrp = if (useNrSignal) displayState.ssRsrp else displayState.rsrp,
            rsrq = if (useNrSignal) displayState.ssRsrq else displayState.rsrq,
            sinr = if (useNrSignal) displayState.ssSinr else displayState.sinr
        )

        if (playbackFrameIndex != null) {
            val session = playbackSessionKey ?: 0
            if (lastTimelinePlaybackSession != session) {
                cellTimeline = emptyList()
                lastTimelinePlaybackFrameIndex = -1
                lastTimelinePlaybackSession = session
            }

            when {
                lastTimelinePlaybackFrameIndex < 0 -> {
                    cellTimeline = (cellTimeline + point).takeLast(20)
                }
                playbackFrameIndex > lastTimelinePlaybackFrameIndex -> {
                    cellTimeline = (cellTimeline + point).takeLast(20)
                }
                playbackFrameIndex < lastTimelinePlaybackFrameIndex -> {
                    val removeCount = lastTimelinePlaybackFrameIndex - playbackFrameIndex
                    cellTimeline = cellTimeline.dropLast(removeCount.coerceAtMost(cellTimeline.size))
                }
                else -> Unit
            }
            lastTimelinePlaybackFrameIndex = playbackFrameIndex
            return@LaunchedEffect
        }

        if (lastTimelinePlaybackSession != Int.MIN_VALUE) {
            cellTimeline = emptyList()
            lastTimelinePlaybackFrameIndex = -1
            lastTimelinePlaybackSession = Int.MIN_VALUE
        }

        val last = cellTimeline.lastOrNull()
        if (last?.sameCell(point) == true && point.ts - last.ts < 1000L) return@LaunchedEffect
        cellTimeline = (cellTimeline + point).takeLast(20)
    }

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

            PanelDivider()

            LteServingCellRow(
                s = displayState,
                hideSensitive = hideSensitive,
                queryUi = queryUi,
                devEnabled = devEnabled,
                onOpenDetail = { showCellDialog = true },
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
                show = showCellDialog,
                queryUi = queryUi,
                onDismiss = { showCellDialog = false },
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

            val nrDlBwText = remember(s.nrCaInfo, showNrBandwidth) {
                if (!showNrBandwidth) return@remember "-"

                val carriers = s.nrCaInfo?.carriers.orEmpty()
                if (carriers.isEmpty()) return@remember "-"

                fun mhzOf(c: NrCaCarrier): Int = NrcaTranslator.bandwidthMhzOrNull(c.dlBwRaw) ?: 0

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
                    nrCarriers.size == 1 -> stringResource(R.string.network_single_carrier)
                    showNrCaLoading -> stringResource(R.string.state_loading)
                    else -> stringResource(R.string.state_no_data)
                }

                val caChips = remember(nrCarriers) {
                    if (nrCarriers.size < 2) {
                        emptyList()
                    } else {
                        nrCarriers.take(6).map { c ->
                            CaChip(
                                text = NrcaTranslator.toChipText(c.bandRaw, c.dlBwRaw, c.ulBwRaw),
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
                onSubmit = { followVm.submit(displayState) },
                onSubmitSuccess = {
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
                hideSensitive = hideSensitive
            )
        }
    }
}




@Composable
fun KeyValueRow(
    k: String,
    v: String,
    onClick: (() -> Unit)? = null,
    valueColor: Color? = null
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

        Text(
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

private val nrHighSpeedRailwayArfcns = setOf(
    507150,
    527070,
    627744,
    634464,
    423630
)

private fun formatCellType(cellType: String, arfcn: Int?): String {
    if (cellType != "NR" || arfcn == null || arfcn !in nrHighSpeedRailwayArfcns) return cellType
    return "$cellType 高铁专网"
}

private data class CellTimelinePoint(
    val ts: Long,
    val cellType: String,
    val tac: String,
    val pci: String,
    val ci: String,
    val rsrp: String,
    val rsrq: String,
    val sinr: String
) {
    fun sameCell(other: CellTimelinePoint): Boolean {
        return cellType == other.cellType &&
                tac == other.tac &&
                pci == other.pci &&
                ci == other.ci
    }
}

@Composable
private fun CellTimelinePanel(
    points: List<CellTimelinePoint>,
    hideSensitive: Boolean
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val visible = remember(points, expanded) {
        val count = if (expanded) 20 else 5
        points.takeLast(count).asReversed()
    }

    Card(
        modifier = Modifier
            .fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(stringResource(R.string.network_cell_timeline), fontWeight = FontWeight.SemiBold)
                if (points.size > 5) {
                    TextButton(onClick = { expanded = !expanded }) {
                        Text(
                            if (expanded) {
                                stringResource(R.string.action_collapse)
                            } else {
                                stringResource(R.string.action_expand)
                            }
                        )
                    }
                }
            }

            Spacer(Modifier.height(6.dp))

            if (visible.isEmpty()) {
                Text(
                    stringResource(R.string.network_waiting_cell_data),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                visible.forEachIndexed { idx, p ->
                    TimelineRow(
                        point = p,
                        hideSensitive = hideSensitive
                    )
                    if (idx != visible.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 1.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TimelineRow(
    point: CellTimelinePoint,
    hideSensitive: Boolean
) {
    val tac = if (hideSensitive) "***" else point.tac
    val pci = if (hideSensitive) "***" else point.pci
    val ci = if (hideSensitive) "***" else point.ci
    val cellIdLabel = if (point.cellType == "LTE") "ECI" else "NCI"
    val dividerColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.75f)
    val shape = RoundedCornerShape(8.dp)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.34f))
            .border(1.dp, dividerColor, shape)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
        ) {
            TimelineCellValue("TAC", tac, Modifier.weight(1f))
            TimelineGridDivider(dividerColor)
            TimelineCellValue("PCI", pci, Modifier.weight(1f))
            TimelineGridDivider(dividerColor)
            TimelineCellValue(cellIdLabel, ci, Modifier.weight(1.25f), maxLines = 2)
        }

        HorizontalDivider(color = dividerColor)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
        ) {
            TimelineSignalValue(
                label = "RSRP",
                valueText = point.rsrp,
                fraction = fractionFromDbm(point.rsrp, min = -140.0, max = -70.0),
                modifier = Modifier.weight(1f)
            )
            TimelineGridDivider(dividerColor)
            TimelineSignalValue(
                label = "RSRQ",
                valueText = point.rsrq,
                fraction = fractionFromDb(point.rsrq, min = -20.0, max = -3.0),
                modifier = Modifier.weight(1f)
            )
            TimelineGridDivider(dividerColor)
            TimelineSignalValue(
                label = "SINR",
                valueText = point.sinr,
                fraction = fractionFromDb(point.sinr, min = -10.0, max = 30.0),
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun TimelineGridDivider(color: Color) {
    VerticalDivider(
        modifier = Modifier.fillMaxHeight(),
        color = color
    )
}

@Composable
private fun TimelineCellValue(
    label: String,
    value: String,
    modifier: Modifier,
    maxLines: Int = 1
) {
    Column(
        modifier = modifier.padding(horizontal = 7.dp, vertical = 5.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun TimelineSignalValue(
    label: String,
    valueText: String,
    fraction: Float,
    modifier: Modifier
) {
    val target = fraction.coerceIn(0f, 1f)
    Column(
        modifier = modifier.padding(horizontal = 7.dp, vertical = 5.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = valueText.takeIf { it.isNotBlank() } ?: "-",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(5.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(target)
                    .clip(RoundedCornerShape(999.dp))
                    .background(
                        if (valueText == "-" || valueText.isBlank()) {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)
                        } else {
                            qualityColor(target)
                        }
                    )
            )
        }
    }
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
    val target = fraction.coerceIn(0f, 1f)

    val animated by animateFloatAsState(
        targetValue = target,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy, // use MediumBouncy for bouncier effect
            stiffness = Spring.StiffnessLow
        ),
        label = "signalBar"
    )

    val barColor = qualityColor(animated)

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            modifier = Modifier.width(92.dp),
            style = MaterialTheme.typography.bodyMedium
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .height(12.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(animated)
                    .clip(RoundedCornerShape(999.dp))
                    .background(
                        if (valueText == "-" || valueText.isBlank()) {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)
                        } else barColor
                    )
            )

            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(1.dp)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    .align(Alignment.Center)
            )
        }

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

/**
 */
private fun qualityColor(f: Float): Color {

    val bad = Color(0xFFFF3B30)   // red
    val mid = Color(0xFFFFCC00)   // yellow
    val good = Color(0xFF34C759)  // green

    return when {
        f <= 0f -> bad
        f >= 1f -> good
        f < 0.5f -> lerpColor(bad, mid, f / 0.5f)
        else -> lerpColor(mid, good, (f - 0.5f) / 0.5f)
    }
}


private fun lerpColor(a: Color, b: Color, t: Float): Color {
    val tt = t.coerceIn(0f, 1f)
    return Color(
        red = a.red + (b.red - a.red) * tt,
        green = a.green + (b.green - a.green) * tt,
        blue = a.blue + (b.blue - a.blue) * tt,
        alpha = a.alpha + (b.alpha - a.alpha) * tt
    )
}



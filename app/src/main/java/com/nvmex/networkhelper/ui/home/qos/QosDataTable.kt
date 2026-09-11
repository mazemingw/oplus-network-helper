package com.nvmex.networkhelper.ui.home.qos

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.nvmex.networkhelper.R
import com.nvmex.networkhelper.model.network.GlobalQosEvent
import com.nvmex.networkhelper.model.network.QosData
import com.nvmex.networkhelper.viewmodel.signal.QosViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun QosDataTable(
    modifier: Modifier = Modifier,
    viewModel: QosViewModel = hiltViewModel(),
    overrideQosBySubId: Map<Int, QosData>? = null,
    overrideGlobalEvent: GlobalQosEvent? = null
) {
    val liveDisplayQosMap by viewModel.displayQosBySubId.collectAsState()
    val liveCurrentQosMap by viewModel.currentQosBySubId.collectAsState()
    val liveGlobalEvent by viewModel.globalEvent.collectAsState()
    val displayQosMap = overrideQosBySubId?.mapValues { (_, qos) ->
        with(viewModel) { qos.toDisplayData() }
    } ?: liveDisplayQosMap
    val currentQosMap = overrideQosBySubId ?: liveCurrentQosMap
    val globalEvent = overrideGlobalEvent ?: liveGlobalEvent

    val isChinese = isCurrentLocaleChinese()

    val subIds = displayQosMap.keys.sorted()
    var selectedSubId by remember(subIds) {
        mutableIntStateOf(subIds.firstOrNull() ?: -1)
    }

    if (selectedSubId !in subIds && subIds.isNotEmpty()) {
        selectedSubId = subIds.first()
    }

    val displayQos = displayQosMap[selectedSubId]
    val currentQos = currentQosMap[selectedSubId]

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = stringResource(R.string.qos_title),
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = stringResource(
                            R.string.qos_updated,
                            currentQos?.formatTime() ?: stringResource(R.string.qos_waiting)
                        ),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (subIds.isNotEmpty()) {
                SubIdChipTabs(
                    subIds = subIds,
                    selectedSubId = selectedSubId,
                    isChinese = isChinese,
                    onSelect = { selectedSubId = it }
                )
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
            )

            if (displayQos == null) {
                Text(
                    text = stringResource(R.string.qos_empty_per_sim),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                DualSectionRow(
                    left = {
                        QosSectionBlock(title = stringResource(R.string.qos_section_basic)) {
                            QosKeyValueRow("SubId", displayQos.subId, isChinese)
                            QosKeyValueRow("Rat", displayQos.rat, isChinese)
                            QosKeyValueRow("AccessMode", displayQos.accessMode, isChinese)
                            QosKeyValueRow("EndcState", displayQos.endcState, isChinese)
                            QosKeyValueRow("Arfcn", displayQos.arfcn, isChinese)
                            QosKeyValueRow("Pci", displayQos.pci, isChinese)
                            QosKeyValueRow("Band", displayQos.band, isChinese)
                            QosKeyValueRow("DLBW", displayQos.dlBw, isChinese)
                        }
                    },
                    right = {
                        QosSectionBlock(title = stringResource(R.string.qos_section_signal)) {
                            QosKeyValueRow("Rsrp", displayQos.rsrp, isChinese)
                            QosKeyValueRow("Rsrq", displayQos.rsrq, isChinese)
                            QosKeyValueRow("Snr", displayQos.snr, isChinese)
                            QosKeyValueRow("SvcStatus", displayQos.svcStatus, isChinese)
                            QosKeyValueRow("Latency", displayQos.latency, isChinese)
                            QosKeyValueRow("CellId", displayQos.cellId, isChinese)
                            QosKeyValueRow("Nr5GScs", displayQos.nr5gScs, isChinese)
                        }
                    }
                )

                DualSectionRow(
                    left = {
                        QosSectionBlock(title = stringResource(R.string.qos_section_uplink)) {
                            QosKeyValueRow("TimeStamp", displayQos.ulTimeStamp, isChinese)
                            QosKeyValueRow("PDCPNumDataPdu", displayQos.ulPdcpNumDataPdu, isChinese)
                            QosKeyValueRow("PDCPNumDropPdu", displayQos.ulPdcpNumDropPdu, isChinese)
                            QosKeyValueRow("PDCPTput", displayQos.ulPdcpTput, isChinese)
                            QosKeyValueRow("RLCNumDataPdu", displayQos.ulRlcNumDataPdu, isChinese)
                            QosKeyValueRow("RLCNumRetxPdu", displayQos.ulRlcRetx, isChinese)
                            QosKeyValueRow("Grant", displayQos.ulGrant, isChinese)
                            QosKeyValueRow("BSR", displayQos.ulBsr, isChinese)
                            QosKeyValueRow("Bler", displayQos.ulBler, isChinese)
                        }
                    },
                    right = {
                        QosSectionBlock(title = stringResource(R.string.qos_section_downlink)) {
                            QosKeyValueRow("TimeStamp", displayQos.dlTimeStamp, isChinese)
                            QosKeyValueRow("PDCPNumDataPdu", displayQos.dlPdcpNumDataPdu, isChinese)
                            QosKeyValueRow("PDCPTput", displayQos.dlPdcpTput, isChinese)
                            QosKeyValueRow("RLCNumDataPdu", displayQos.dlRlcNumDataPdu, isChinese)
                            QosKeyValueRow("RLCNumRetxPdu", displayQos.dlRlcRetx, isChinese)
                            QosKeyValueRow("RLCNumDropPdu", displayQos.dlRlcDrop, isChinese)
                            QosKeyValueRow("MACPaddingBytes", displayQos.dlMacPaddingBytes, isChinese)
                            QosKeyValueRow("PdcpNumMissToUppPdu", displayQos.dlPdcpNumMissToUppPdu, isChinese)
                            QosKeyValueRow("Bler", displayQos.dlBler, isChinese)
                        }
                    }
                )

                DualSectionRow(
                    left = {
                        QosSectionBlock(title = stringResource(R.string.qos_section_sim_state)) {
                            QosKeyValueRow("IsDualSimConflict", displayQos.isDualSimConflict, isChinese)
                            QosKeyValueRow("LinkReport", displayQos.linkReport, isChinese)
                            QosKeyValueRow("LimitSpeedFlag", displayQos.limitSpeedFlag, isChinese)
                            QosKeyValueRow("LimitSpeedRate", displayQos.limitSpeedRate, isChinese)
                            QosKeyValueRow("CellLoad", displayQos.cellLoad, isChinese)
                        }
                    },
                    right = {
                        QosSectionBlock(title = stringResource(R.string.qos_section_dual_sim_rach)) {
                            QosKeyValueRow("Sub1RrcState", displayQos.sub1RrcState, isChinese)
                            QosKeyValueRow("Sub2RrcState", displayQos.sub2RrcState, isChinese)
                            QosKeyValueRow("RachCount", displayQos.rachCount, isChinese)
                            QosKeyValueRow("RachAbortCount", displayQos.rachAbortCount, isChinese)
                        }
                    }
                )

                DualSectionRow(
                    left = {
                        QosSectionBlock(title = stringResource(R.string.qos_section_power)) {
                            QosKeyValueRow("CalcPower", displayQos.calcPower, isChinese)
                            QosKeyValueRow("Mtpl", displayQos.mtpl, isChinese)
                            QosKeyValueRow("PathLoss", displayQos.pathLoss, isChinese)
                        }
                    },
                    right = {
                        QosSectionBlock(title = stringResource(R.string.qos_section_other)) {
                            QosKeyValueRow("FbrxCount", displayQos.fbrxCount, isChinese)
                            QosKeyValueRow("CellLoad", displayQos.cellLoad, isChinese)
                        }
                    }
                )
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
            )

            GlobalEventSection(
                event = globalEvent,
                isChinese = isChinese
            )
        }
    }
}

@Composable
private fun GlobalEventSection(
    event: GlobalQosEvent?,
    isChinese: Boolean
) {
    QosSectionBlock(
        title = stringResource(R.string.qos_section_global_events)
    ) {
        if (event == null) {
            Text(
                text = stringResource(R.string.qos_empty_global_events),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@QosSectionBlock
        }

        DualSectionRow(
            left = {
                QosSectionBlock(title = stringResource(R.string.qos_section_counters)) {
                    QosKeyValueRow("GlobalRlfCount", event.rlfCount.toString(), isChinese, allowFullWrap = true)
                    QosKeyValueRow("GlobalRachWithUlGrantCount", event.rachWithUlGrantCount.toString(), isChinese, allowFullWrap = true)
                    QosKeyValueRow("GlobalCellChangeCount", event.cellChangeCount.toString(), isChinese, allowFullWrap = true)
                    QosKeyValueRow("GlobalIsRedirectionOccur", mapBoolean(event.isRedirectionOccur, isChinese), isChinese, allowFullWrap = true)
                }
            },
            right = {
                QosSectionBlock(title = stringResource(R.string.qos_section_paging_mobility)) {
                    QosKeyValueRow("PagingSysMode", mapSysMode(event.pagingSysMode), isChinese, allowFullWrap = true)
                    QosKeyValueRow("MobilitySysMode", mapSysMode(event.mobilitySysMode), isChinese, allowFullWrap = true)
                    QosKeyValueRow("MobilityType", mapMobilityType(event.mobilityType), isChinese, allowFullWrap = true)
                    QosKeyValueRow("MobilityStatus", mapMobilityStatus(event.mobilityStatus), isChinese, allowFullWrap = true)
                    QosKeyValueRow("MobilitySourceRat", mapRat(event.mobilitySourceRat), isChinese, allowFullWrap = true)
                    QosKeyValueRow("MobilityTargetRat", mapRat(event.mobilityTargetRat), isChinese, allowFullWrap = true)
                }
            }
        )

        DualSectionRow(
            left = {
                QosSectionBlock(title = stringResource(R.string.qos_section_nas_state)) {
                    QosKeyValueRow("NasSysMode", mapSysMode(event.nasSysMode), isChinese, allowFullWrap = true)
                    QosKeyValueRow("EmmState", mapEmmState(event.emmState, isChinese), isChinese, allowFullWrap = true)
                    QosKeyValueRow("EmmSubState", mapEmmSubState(event.emmSubState, isChinese), isChinese, allowFullWrap = true)
                    QosKeyValueRow("Mm5gState", map5gmmState(event.mm5gState, isChinese), isChinese, allowFullWrap = true)
                    QosKeyValueRow("Mm5gSubState", map5gmmSubState(event.mm5gSubState, isChinese), isChinese, allowFullWrap = true)
                    QosKeyValueRow("PlmnId", formatPlmnId(event.plmnId), isChinese, allowFullWrap = true)
                }
            },
            right = {
                QosSectionBlock(title = stringResource(R.string.qos_section_notes)) {
                    Text(
                        text = stringResource(R.string.qos_global_notes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        )
    }
}

@Composable
private fun isCurrentLocaleChinese(): Boolean {
    val configuration = LocalConfiguration.current
    val locale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        configuration.locales[0]
    } else {
        @Suppress("DEPRECATION")
        configuration.locale
    }
    return locale.language.equals("zh", ignoreCase = true)
}

@Composable
private fun DualSectionRow(
    left: @Composable () -> Unit,
    right: @Composable () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(modifier = Modifier.weight(1f)) {
            left()
        }
        Box(modifier = Modifier.weight(1f)) {
            right()
        }
    }
}

@Composable
private fun SubIdChipTabs(
    subIds: List<Int>,
    selectedSubId: Int,
    isChinese: Boolean,
    onSelect: (Int) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        subIds.forEachIndexed { index, subId ->
            val selected = subId == selectedSubId

            val title = if (isChinese) {
                "SIM${index + 1}"
            } else {
                "SIM ${index + 1}"
            }

            val subText = if (isChinese) {
                "SubId $subId"
            } else {
                "SubId $subId"
            }

            Surface(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { onSelect(subId) },
                shape = RoundedCornerShape(16.dp),
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                },
                tonalElevation = if (selected) 2.dp else 0.dp,
                shadowElevation = 0.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = if (selected) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                    )
                    Text(
                        text = subText,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selected) {
                            MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.88f)
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun QosSectionBlock(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = title,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary
        )

        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
        )

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            content()
        }
    }
}

private fun mapBoolean(flag: Int, isChinese: Boolean): String {
    return if (flag == 1) {
        if (isChinese) "是" else "Yes"
    } else {
        if (isChinese) "否" else "No"
    }
}

private fun mapSysMode(sysMode: Int): String = when (sysMode) {
    0 -> "NONE (0)"
    1 -> "GSM (1)"
    2 -> "UMTS (2)"
    3 -> "C2K (3)"
    4 -> "LTE (4)"
    5 -> "NR (5)"
    254 -> "OTHER (254)"
    255 -> "INVALID (255)"
    else -> "UNKNOWN($sysMode)"
}

private fun mapRat(rat: Int): String = mapSysMode(rat)

private fun zhEn(zh: String, en: String, isChinese: Boolean): String {
    return if (isChinese) zh else en
}

private fun mapMobilityType(type: Int): String = when (type) {
    0 -> "NONE (0)"
    1 -> "HO (1)"
    2 -> "HO_IRAT (2)"
    3 -> "REDIR (3)"
    4 -> "REDIR_IRAT (4)"
    5 -> "RESEL (5)"
    6 -> "RESEL_IRAT (6)"
    else -> "UNKNOWN($type)"
}

private fun mapMobilityStatus(status: Int): String = when (status) {
    0 -> "NONE (0)"
    1 -> "BEGIN (1)"
    2 -> "SUCCESS (2)"
    3 -> "FAIL (3)"
    255 -> "INVALID (255)"
    else -> "UNKNOWN($status)"
}

private fun mapEmmState(state: Int, isChinese: Boolean): String = when (state) {
    0 -> zhEn("无 (0)", "None (0)", isChinese)
    1 -> zhEn("空状态 (1)", "Null state (1)", isChinese)
    2 -> zhEn("未注册 (2)", "Deregistered (2)", isChinese)
    3 -> zhEn("注册发起中 (3)", "Register initiated (3)", isChinese)
    4 -> zhEn("已注册 (4)", "Registered (4)", isChinese)
    5 -> zhEn("TAU发起中 (5)", "TAU initiated (5)", isChinese)
    6 -> zhEn("业务请求发起中 (6)", "Service request initiated (6)", isChinese)
    7 -> zhEn("去注册发起中 (7)", "Deregister initiated (7)", isChinese)
    255 -> zhEn("无效 (255)", "Invalid (255)", isChinese)
    else -> zhEn("未知($state)", "Unknown($state)", isChinese)
}

private fun mapEmmSubState(subState: Int, isChinese: Boolean): String = when (subState) {
    0 -> zhEn("无 (0)", "None (0)", isChinese)
    1 -> zhEn("无 IMSI (1)", "No IMSI (1)", isChinese)
    2 -> zhEn("搜网中 (2)", "Searching network (2)", isChinese)
    3 -> zhEn("需要附着 (3)", "Attach needed (3)", isChinese)
    4 -> zhEn("无可用小区 (4)", "No suitable cell (4)", isChinese)
    5 -> zhEn("尝试附着中 (5)", "Trying to attach (5)", isChinese)
    6 -> zhEn("正常服务 (6)", "Normal service (6)", isChinese)
    7 -> zhEn("受限服务 (7)", "Limited service (7)", isChinese)
    11 -> zhEn("已注册-正常服务 (11)", "Registered - normal service (11)", isChinese)
    12 -> zhEn("已注册-需要更新 (12)", "Registered - update needed (12)", isChinese)
    13 -> zhEn("已注册-尝试更新 (13)", "Registered - trying update (13)", isChinese)
    14 -> zhEn("已注册-无可用小区 (14)", "Registered - no suitable cell (14)", isChinese)
    15 -> zhEn("已注册-搜网中 (15)", "Registered - searching network (15)", isChinese)
    16 -> zhEn("已注册-受限服务 (16)", "Registered - limited service (16)", isChinese)
    17 -> zhEn("已注册-IMSI去附着发起中 (17)", "Registered - IMSI detach initiated (17)", isChinese)
    18 -> zhEn("已注册-尝试MM更新 (18)", "Registered - trying MM update (18)", isChinese)
    21 -> zhEn("等待网络响应 (21)", "Waiting for network response (21)", isChinese)
    22 -> zhEn("等待ESM响应 (22)", "Waiting for ESM response (22)", isChinese)
    255 -> zhEn("无效 (255)", "Invalid (255)", isChinese)
    else -> zhEn("未知($subState)", "Unknown($subState)", isChinese)
}

private fun map5gmmState(state: Int, isChinese: Boolean): String = when (state) {
    0 -> zhEn("无 (0)", "None (0)", isChinese)
    1 -> zhEn("空状态 (1)", "Null state (1)", isChinese)
    2 -> zhEn("未注册 (2)", "Deregistered (2)", isChinese)
    3 -> zhEn("注册发起中 (3)", "Register initiated (3)", isChinese)
    4 -> zhEn("已注册 (4)", "Registered (4)", isChinese)
    5 -> zhEn("去注册发起中 (5)", "Deregister initiated (5)", isChinese)
    6 -> zhEn("业务请求发起中 (6)", "Service request initiated (6)", isChinese)
    255 -> zhEn("无效 (255)", "Invalid (255)", isChinese)
    else -> zhEn("未知($state)", "Unknown($state)", isChinese)
}

private fun map5gmmSubState(subState: Int, isChinese: Boolean): String = when (subState) {
    0 -> zhEn("无 (0)", "None (0)", isChinese)
    1 -> zhEn("无 SUPI (1)", "No SUPI (1)", isChinese)
    2 -> zhEn("搜 PLMN 中 (2)", "Searching PLMN (2)", isChinese)
    3 -> zhEn("无可用小区 (3)", "No suitable cell (3)", isChinese)
    4 -> zhEn("尝试注册更新 (4)", "Trying registration update (4)", isChinese)
    5 -> zhEn("受限服务 (5)", "Limited service (5)", isChinese)
    6 -> zhEn("正常服务 (6)", "Normal service (6)", isChinese)
    7 -> zhEn("需要初始注册 (7)", "Initial registration needed (7)", isChinese)
    8 -> zhEn("ECALL 非激活 (8)", "ECALL inactive (8)", isChinese)
    11 -> zhEn("已注册-正常服务 (11)", "Registered - normal service (11)", isChinese)
    12 -> zhEn("已注册-非许可服务 (12)", "Registered - non-allowed service (12)", isChinese)
    13 -> zhEn("已注册-尝试更新 (13)", "Registered - trying update (13)", isChinese)
    14 -> zhEn("已注册-受限服务 (14)", "Registered - limited service (14)", isChinese)
    15 -> zhEn("已注册-搜网中 (15)", "Registered - searching network (15)", isChinese)
    16 -> zhEn("已注册-无可用小区 (16)", "Registered - no suitable cell (16)", isChinese)
    17 -> zhEn("已注册-需要更新 (17)", "Registered - update needed (17)", isChinese)
    21 -> zhEn("等待网络响应 (21)", "Waiting for network response (21)", isChinese)
    255 -> zhEn("无效 (255)", "Invalid (255)", isChinese)
    else -> zhEn("未知($subState)", "Unknown($subState)", isChinese)
}

private fun formatPlmnId(plmnId: Int): String = when (plmnId) {
    0, -1, 16777215 -> "NONE"
    else -> plmnId.toString()
}

fun translateLabel(label: String, zh: Boolean): String {
    return when (label) {
        "SubId" -> if (zh) "卡槽ID" else "SIM"
        "Rat" -> if (zh) "无线制式" else "RAT"
        "AccessMode" -> if (zh) "5G接入模式" else "5G Access"
        "EndcState" -> if (zh) "EN-DC状态" else "EN-DC"
        "Arfcn" -> if (zh) "频点" else "ARFCN"
        "Pci" -> if (zh) "PCI" else "PCI"
        "Band" -> if (zh) "频段" else "Band"
        "DLBW" -> if (zh) "下行带宽" else "DL BW"

        "Rsrp" -> if (zh) "参考信号功率" else "RSRP"
        "Rsrq" -> if (zh) "参考信号质量" else "RSRQ"
        "Snr" -> if (zh) "信噪比" else "SNR"
        "SvcStatus" -> if (zh) "服务状态" else "Service"
        "Latency" -> if (zh) "时延" else "Latency"
        "CellId" -> if (zh) "小区ID" else "Cell ID"
        "Nr5GScs" -> if (zh) "子载波间隔" else "SCS"

        "TimeStamp" -> if (zh) "时间戳" else "Time"
        "PDCPNumDataPdu" -> if (zh) "PDCP数据包" else "PDCP Data"
        "PDCPNumDropPdu" -> if (zh) "PDCP丢包" else "PDCP Drop"
        "PDCPTput" -> if (zh) "吞吐" else "Throughput"
        "RLCNumDataPdu" -> if (zh) "RLC数据包" else "RLC Data"
        "RLCNumRetxPdu" -> if (zh) "RLC重传" else "RLC Retx"
        "RLCNumDropPdu" -> if (zh) "RLC丢包" else "RLC Drop"
        "Grant" -> if (zh) "Grant" else "Grant"
        "BSR" -> if (zh) "BSR" else "BSR"
        "Bler" -> if (zh) "BLER" else "BLER"
        "MACPaddingBytes" -> if (zh) "MAC填充" else "MAC Pad"
        "PdcpNumMissToUppPdu" -> if (zh) "PDCP上送丢包" else "PDCP Miss"

        "IsDualSimConflict" -> if (zh) "双卡冲突" else "SIM Conflict"
        "LinkReport" -> if (zh) "链路报告" else "Link Report"
        "LimitSpeedFlag" -> if (zh) "限速标记" else "Throttle Flag"
        "LimitSpeedRate" -> if (zh) "限速速率" else "Throttle Rate"

        "Sub1RrcState" -> if (zh) "卡1 RRC" else "SIM1 RRC"
        "Sub2RrcState" -> if (zh) "卡2 RRC" else "SIM2 RRC"

        "RachCount" -> if (zh) "RACH次数" else "RACH Count"
        "RachAbortCount" -> if (zh) "RACH中止" else "RACH Abort"

        "CalcPower" -> if (zh) "计算功率" else "Power"
        "Mtpl" -> if (zh) "最大发射功率" else "MTPL"
        "PathLoss" -> if (zh) "路径损耗" else "Path Loss"

        "FbrxCount" -> if (zh) "FB Rx计数" else "FBRx"
        "CellLoad" -> if (zh) "小区负载" else "Load"

        "GlobalRlfCount" -> if (zh) "全局链路失败次数" else "Global RLF"
        "GlobalRachWithUlGrantCount" -> if (zh) "全局授权RACH次数" else "Global RACH Grant"
        "GlobalCellChangeCount" -> if (zh) "全局小区变更次数" else "Global Cell Change"
        "GlobalIsRedirectionOccur" -> if (zh) "全局发生重定向" else "Global Redirect"

        "PagingSysMode" -> if (zh) "Paging制式" else "Paging SysMode"
        "MobilitySysMode" -> if (zh) "Mobility制式" else "Mobility SysMode"
        "MobilityType" -> if (zh) "切换类型" else "Mobility Type"
        "MobilityStatus" -> if (zh) "切换状态" else "Mobility Status"
        "MobilitySourceRat" -> if (zh) "源制式" else "Source RAT"
        "MobilityTargetRat" -> if (zh) "目标制式" else "Target RAT"

        "NasSysMode" -> if (zh) "NAS制式" else "NAS SysMode"
        "EmmState" -> if (zh) "EMM状态" else "EMM State"
        "EmmSubState" -> if (zh) "EMM子状态" else "EMM SubState"
        "Mm5gState" -> if (zh) "5GMM状态" else "5GMM State"
        "Mm5gSubState" -> if (zh) "5GMM子状态" else "5GMM SubState"
        "PlmnId" -> if (zh) "PLMN ID" else "PLMN ID"

        else -> label
    }
}

@Composable
fun QosKeyValueRow(
    label: String,
    value: String?,
    isChinese: Boolean,
    allowFullWrap: Boolean = false
) {
    val displayLabel = translateLabel(label, isChinese)
    val displayValue = value?.takeIf { it.isNotBlank() } ?: "-"
    val labelVisualLength = displayLabel.sumOf { char ->
        if (char.code in 0x2E80..0x9FFF) 2 else 1
    }
    val shouldStack = (if (allowFullWrap) labelVisualLength >= 14 else displayLabel.length >= 12) ||
            displayValue.length >= 18 ||
            displayValue.contains('\n')

    if (shouldStack) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 1.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            Text(
                text = displayLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (allowFullWrap) Int.MAX_VALUE else 1,
                overflow = if (allowFullWrap) TextOverflow.Visible else TextOverflow.Ellipsis
            )
            Text(
                text = displayValue,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.End,
                maxLines = if (allowFullWrap) Int.MAX_VALUE else 3,
                overflow = if (allowFullWrap) TextOverflow.Visible else TextOverflow.Ellipsis
            )
        }
        return
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = displayLabel,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
            maxLines = if (allowFullWrap) Int.MAX_VALUE else 1,
            overflow = if (allowFullWrap) TextOverflow.Visible else TextOverflow.Ellipsis
        )
        Text(
            text = displayValue,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.End,
            maxLines = if (allowFullWrap) Int.MAX_VALUE else 2,
            overflow = if (allowFullWrap) TextOverflow.Visible else TextOverflow.Ellipsis
        )
    }
}

private fun com.nvmex.networkhelper.model.network.QosData.formatTime(): String {
    if (timestamp <= 0L) return "-"
    val epochMs = if (timestamp < 1_000_000_000_000L) timestamp * 1000L else timestamp
    val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        .withZone(ZoneId.systemDefault())
    return runCatching { formatter.format(Instant.ofEpochMilli(epochMs)) }
        .getOrElse { epochMs.toString() }
}

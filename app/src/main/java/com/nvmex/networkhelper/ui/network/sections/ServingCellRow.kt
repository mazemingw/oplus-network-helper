package com.nvmex.networkhelper.ui.network.sections

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.nvmex.networkhelper.R
import com.nvmex.networkhelper.model.network.NetworkPanelUiState
import com.nvmex.networkhelper.ui.network.KeyValueRow
import com.nvmex.networkhelper.util.network.isDegradedCellMatchLevel
import com.nvmex.networkhelper.viewmodel.menu.CellQueryUiState

@Composable
fun LteServingCellRow(
    s: NetworkPanelUiState,
    hideSensitive: Boolean,
    queryUi: CellQueryUiState,
    devEnabled: Boolean,
    onOpenDetail: () -> Unit,
    onFollowRecord: () -> Unit,
    label: String = "服务基站",
    highlightBlue: Color = Color(0xFF448AFF),
) {
    if (s.cellType != "LTE" && s.cellType != "NR") return

    val context = LocalContext.current
    val valueText = when (val raw = queryUi.toServingCellDisplayText(devEnabled)) {
        "未查询" -> context.getString(R.string.network_cell_query_idle)
        "查询中..." -> context.getString(R.string.network_cell_query_loading)
        "查询失败" -> context.getString(R.string.network_cell_query_failed)
        "未知" -> context.getString(R.string.state_unknown)
        "随行随录" -> context.getString(R.string.network_follow_record)
        else -> raw
    }
    val canOpenDetail = queryUi.canOpenServingCellDetail()
    val canFollowRecord = devEnabled && queryUi.isServingCellUnknown()

    KeyValueRow(
        k = label,
        v = if (hideSensitive) "***" else valueText,
        onClick = when {
            canOpenDetail -> onOpenDetail
            canFollowRecord -> onFollowRecord
            else -> null
        },
        valueColor = if (canOpenDetail || canFollowRecord) {
            highlightBlue
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
    )
}

fun CellQueryUiState.toServingCellValueText(): String {
    return when (this) {
        CellQueryUiState.Idle -> "未查询"
        CellQueryUiState.Loading -> "查询中..."
        is CellQueryUiState.Error -> "查询失败"

        is CellQueryUiState.LteSuccess -> {
            val first = resp.data.firstOrNull()
            val name = first?.cell_name?.takeIf { it.isNotBlank() } ?: "未知"
            val isFallback = isDegradedCellMatchLevel(resp.match_level)
            if (isFallback && name != "未知") "$name [?]" else name
        }

        is CellQueryUiState.NrSuccess -> {
            val first = resp.data.firstOrNull()
            val name = first?.cell_name?.takeIf { it.isNotBlank() } ?: "未知"
            val isFallback = isDegradedCellMatchLevel(resp.match_level)
            if (isFallback && name != "未知") "$name [?]" else name
        }
    }
}

fun CellQueryUiState.canOpenServingCellDetail(): Boolean {
    return when (this) {
        is CellQueryUiState.LteSuccess -> resp.data.isNotEmpty()
        is CellQueryUiState.NrSuccess -> resp.data.isNotEmpty()
        else -> false
    }
}

/**
 * 仅在查询成功后，且最终展示名为“未知”时，认为是可随行随录的未知基站。
 */
fun CellQueryUiState.isServingCellUnknown(): Boolean {
    return when (this) {
        is CellQueryUiState.LteSuccess,
        is CellQueryUiState.NrSuccess -> toServingCellValueText() == "未知"

        else -> false
    }
}

fun CellQueryUiState.toServingCellDisplayText(devEnabled: Boolean): String {
    val raw = toServingCellValueText()
    return if (devEnabled && isServingCellUnknown()) "随行随录" else raw
}

fun CellQueryUiState.canClickServingCell(devEnabled: Boolean): Boolean {
    return canOpenServingCellDetail() || (devEnabled && isServingCellUnknown())
}

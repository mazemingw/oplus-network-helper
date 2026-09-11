package com.nvmex.networkhelper.ui.home

import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import com.nvmex.networkhelper.R
import com.nvmex.networkhelper.model.network.NetworkPanelUiState

@Composable
fun RealSignalIndicator(
    state: NetworkPanelUiState,
    modifier: Modifier = Modifier
) {
    val rsrp = remember(state) { selectRsrp(state) }
    val level = remember(rsrp) { scoreRsrp(rsrp) }

    val iconRes = when (level) {
        4 -> R.drawable.signal_cellular_4_bar_24px
        3 -> R.drawable.signal_cellular_3_bar_24px
        2 -> R.drawable.signal_cellular_2_bar_24px
        1 -> R.drawable.signal_cellular_1_bar_24px
        0 -> R.drawable.signal_cellular_0_bar_24px
        else -> R.drawable.signal_cellular_0_bar_24px
    }

    Icon(
        painter = painterResource(id = iconRes),
        contentDescription = "真实信号",
        modifier = modifier,
        // 顶栏操作图标统一使用主题主色；信号强弱仅通过图标格数表达。
        tint = MaterialTheme.colorScheme.primary
    )
}

private fun selectRsrp(s: NetworkPanelUiState): Double? {
    val isNrLike = s.cellType.equals("NR", ignoreCase = true) ||
            s.dataNetworkType.equals("NR", ignoreCase = true)
    val rsrpText = if (isNrLike && s.ssRsrp != "-") s.ssRsrp else s.rsrp
    return parseFirstNumber(rsrpText)
}

private fun scoreRsrp(v: Double?): Int {
    if (v == null) return 0
    return when {
        v >= -85.0 -> 4
        v >= -95.0 -> 3
        v >= -105.0 -> 2
        v >= -115.0 -> 1
        else -> 0
    }
}

private fun parseFirstNumber(s: String?): Double? {
    val raw = s?.trim().orEmpty()
    if (raw.isEmpty() || raw == "-") return null
    val m = Regex("""-?\d+(\.\d+)?""").find(raw) ?: return null
    return m.value.toDoubleOrNull()
}

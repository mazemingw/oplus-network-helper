package com.nvmex.networkhelper.util.network

import android.os.Build
import android.os.SystemClock
import android.telephony.*
import com.nvmex.networkhelper.model.network.CellTimelinePoint
import com.nvmex.networkhelper.model.network.NetworkPanelUiState
import com.nvmex.networkhelper.util.qmi.qmiNrBandsForChannel

/** Only the per-subscription callback is accepted; a global allCellInfo fallback can mix SIMs. */
internal fun telephonyNeighbors(cells: List<CellInfo>, state: NetworkPanelUiState): List<CellTimelinePoint> {
    val now = System.currentTimeMillis()
    val elapsed = SystemClock.elapsedRealtime()
    return cells.take(128).mapNotNull { cell ->
        if (cell.isRegistered || cell.cellConnectionStatus in setOf(CellInfo.CONNECTION_PRIMARY_SERVING, CellInfo.CONNECTION_SECONDARY_SERVING)) return@mapNotNull null
        val age = elapsed - cell.timeStamp / 1_000_000
        if (cell.timeStamp <= 0 || age !in 0..12000) return@mapNotNull null
        val point = when (cell) {
            is CellInfoLte -> {
                val id = cell.cellIdentity
                if (id.pci !in 0..503 || id.earfcn !in 0..262143) return@mapNotNull null
                CellTimelinePoint(now - age, "LTE", id.tac.toString(), id.pci.toString(), "",
                    LTE_GLOBAL_BANDS.firstOrNull { id.earfcn in it.earfcnStart..it.earfcnEnd }?.band?.toString().orEmpty(),
                    cell.cellSignalStrength.rsrp.toString(), "—", "—", id.earfcn.toString(), id.mccString.orEmpty(), id.mncString.orEmpty())
            }
            is CellInfoNr -> {
                val id = cell.cellIdentity as CellIdentityNr
                if (id.pci !in 0..1007 || id.nrarfcn !in 1..3279165) return@mapNotNull null
                val band = if (Build.VERSION.SDK_INT >= 30) id.bands.firstOrNull()?.toString() else null
                CellTimelinePoint(now - age, "NR", id.tac.toString(), id.pci.toString(), "",
                    band ?: qmiNrBandsForChannel(id.nrarfcn, state.mcc, state.mnc).singleOrNull()?.toString().orEmpty(),
                    (cell.cellSignalStrength as CellSignalStrengthNr).ssRsrp.toString(), "—", "—", id.nrarfcn.toString(), id.mccString.orEmpty(), id.mncString.orEmpty())
            }
            else -> return@mapNotNull null
        }
        point.takeIf { state.mcc.length != 3 || state.mnc.isBlank() || state.mnc == "-" ||
            it.mcc.isBlank() || it.mnc.isBlank() || it.mcc == state.mcc && it.mnc.toIntOrNull() == state.mnc.toIntOrNull() }
    }
}

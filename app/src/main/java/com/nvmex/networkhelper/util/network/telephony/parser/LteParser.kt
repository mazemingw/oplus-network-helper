package com.nvmex.networkhelper.util.network.telephony.parser

import android.os.Build
import android.telephony.CellInfo
import android.telephony.CellInfoLte
import android.telephony.SignalStrength
import com.nvmex.networkhelper.model.network.NetworkPanelUiState
import com.nvmex.networkhelper.util.network.telephony.SignalStrengthAdapters
import com.nvmex.networkhelper.util.network.utils.formatMhz
import com.nvmex.networkhelper.util.network.utils.lteBandToDuplexCN
import com.nvmex.networkhelper.util.network.utils.lteEarfcnToFreqMhzCN

class LteParser : CellParser {

    override fun canParse(info: CellInfo): Boolean = info is CellInfoLte

    override fun parse(base: NetworkPanelUiState, info: CellInfo, sig: SignalStrength?): NetworkPanelUiState {
        val lte = info as CellInfoLte
        val id = lte.cellIdentity
        val ss = lte.cellSignalStrength

        // SINR（RSSNR）优先从 SignalStrength 补齐，再退回 CellInfoLte 自带
        val sinr = SignalStrengthAdapters.lteRssnrCompat(sig)
            ?: ss.rssnr.takeIf { it != CellInfo.UNAVAILABLE }?.let { "${it / 10.0} dB" }
            ?: "-"

        val eci = id.ci.takeIf { it != CellInfo.UNAVAILABLE }?.toString() ?: "-"
        val tac = id.tac.takeIf { it != CellInfo.UNAVAILABLE }?.toString() ?: "-"
        val pci = id.pci.takeIf { it != CellInfo.UNAVAILABLE }?.toString() ?: "-"

        val earfcnInt = if (Build.VERSION.SDK_INT >= 24) id.earfcn else CellInfo.UNAVAILABLE
        val earfcn = earfcnInt.takeIf { it != CellInfo.UNAVAILABLE }?.toString() ?: "-"

        val bandInt = if (Build.VERSION.SDK_INT >= 30) id.bands.firstOrNull() else null
        val band = bandInt?.toString() ?: "-"

        val duplex = bandInt?.let { lteBandToDuplexCN(it) } ?: "-"

        val dlUl = if (earfcnInt != CellInfo.UNAVAILABLE && bandInt != null) {
            lteEarfcnToFreqMhzCN(bandInt, earfcnInt)
        } else null

        val freqDl = dlUl?.first?.let { formatMhz(it) } ?: "-"
        val freqUl = dlUl?.second?.let { formatMhz(it) } ?: "-"

        val rsrp = ss.rsrp.takeIf { it != CellInfo.UNAVAILABLE }?.let { "$it dBm" } ?: "-"
        val rsrq = ss.rsrq.takeIf { it != CellInfo.UNAVAILABLE }?.let { "$it dB" } ?: "-"

        val rssi = run {
            val v = if (Build.VERSION.SDK_INT >= 29) ss.rssi.takeIf { it != CellInfo.UNAVAILABLE } else null
            val v2 = v ?: ss.dbm.takeIf { it != CellInfo.UNAVAILABLE }
            v2?.let { "$it dBm" } ?: "-"
        }

        return base.copy(
            cellType = "LTE",
            duplex = duplex,
            tac = tac,
            pci = pci,
            ci = eci,
            arfcn = earfcn,
            band = band,
            freqDl = freqDl,
            freqUl = freqUl,

            rssi = rssi,
            rsrp = rsrp,
            rsrq = rsrq,
            sinr = sinr,

            ssRsrp = "-",
            ssRsrq = "-",
            ssSinr = "-",
            csiRsrp = "-",
            csiRsrq = "-",
            csiSinr = "-",

            lastError = null
        )
    }
}
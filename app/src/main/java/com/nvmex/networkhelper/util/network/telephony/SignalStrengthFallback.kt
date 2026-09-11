package com.nvmex.networkhelper.util.network.telephony

import android.telephony.CellInfo
import android.telephony.CellSignalStrengthNr
import android.telephony.SignalStrength
import android.util.Log
import com.nvmex.networkhelper.model.network.NetworkPanelUiState

/**
 * ROM 不提供 NR CellInfo 时，用 SignalStrength 的 NR SS 三件套兜底
 */
class SignalStrengthFallback {

    fun fromSignalStrength(
        base: NetworkPanelUiState,
        sig: SignalStrength?,
        lastErrorMessage: String? = null
    ): NetworkPanelUiState? {
        if (sig == null) return null

        val nrSs = runCatching {
            sig.getCellSignalStrengths(CellSignalStrengthNr::class.java).firstOrNull()
        }.getOrNull() ?: return null

        fun fmtDbm(v: Int): String =
            v.takeIf { it != CellInfo.UNAVAILABLE && it != Int.MAX_VALUE }
                ?.let { "$it dBm" } ?: "-"

        fun fmtDb(v: Int): String =
            v.takeIf { it != CellInfo.UNAVAILABLE && it != Int.MAX_VALUE }
                ?.let { "$it dB" } ?: "-"

        val ssRsrp = fmtDbm(nrSs.ssRsrp)
        val ssRsrq = fmtDb(nrSs.ssRsrq)
        val ssSinr = fmtDb(nrSs.ssSinr)

        //让我来看看NSA模式下给不给 SS SINR三件套
        //Log.d("NR_SIG_DBG", "sig ssRsrp=${nrSs.ssRsrp} ssRsrq=${nrSs.ssRsrq} ssSinr=${nrSs.ssSinr}")


        return base.copy(
            dataNetworkType = "NR",
            cellType = "NR",

            duplex = "-",
            tac = "-",
            pci = "-",
            ci = "-",
            arfcn = "-",
            band = "-",
            freqDl = "-",
            freqUl = "-",

            rssi = "-",
            rsrp = ssRsrp,
            rsrq = ssRsrq,
            sinr = ssSinr,

            ssRsrp = ssRsrp,
            ssRsrq = ssRsrq,
            ssSinr = ssSinr,

            csiRsrp = "-",
            csiRsrq = "-",
            csiSinr = "-",

            lastError = lastErrorMessage ?: "ROM did not provide NR CellInfo; using SignalStrength for NR(SS) signal",
            updatedAt = System.currentTimeMillis()
        )
    }
}

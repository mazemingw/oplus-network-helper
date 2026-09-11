package com.nvmex.networkhelper.util.network.telephony.parser

import android.os.Build
import android.telephony.CellIdentityNr
import android.telephony.CellInfo
import android.telephony.CellInfoNr
import android.telephony.CellSignalStrengthNr
import android.telephony.SignalStrength
import android.util.Log
import com.nvmex.networkhelper.model.network.NetworkPanelUiState
import com.nvmex.networkhelper.util.network.telephony.nrGuessBandByDlMhzCN
import com.nvmex.networkhelper.util.network.utils.formatMhz
import com.nvmex.networkhelper.util.network.utils.nrArfcnToMhz
import com.nvmex.networkhelper.util.network.utils.nrBandToDuplexCN
import com.nvmex.networkhelper.util.network.utils.nrFddDlUlSpacingMhzCN

class NrParser : CellParser {

    override fun canParse(info: CellInfo): Boolean = info is CellInfoNr

    override fun parse(base: NetworkPanelUiState, info: CellInfo, sig: SignalStrength?): NetworkPanelUiState {
        val nr = info as CellInfoNr
        val id = nr.cellIdentity as? CellIdentityNr
        val ss = nr.cellSignalStrength as? CellSignalStrengthNr

        val nci = id?.nci?.takeIf { it != CellInfo.UNAVAILABLE_LONG }?.toString() ?: "-"
        val tac = id?.tac?.takeIf { it != CellInfo.UNAVAILABLE }?.toString() ?: "-"
        val pci = id?.pci?.takeIf { it != CellInfo.UNAVAILABLE }?.toString() ?: "-"

        val arfcnInt = id?.nrarfcn?.takeIf { it != CellInfo.UNAVAILABLE } ?: CellInfo.UNAVAILABLE
        val arfcn = arfcnInt.takeIf { it != CellInfo.UNAVAILABLE }?.toString() ?: "-"

        val dlMhz = arfcnInt.takeIf { it != CellInfo.UNAVAILABLE }?.let { nrArfcnToMhz(it) }

        // 先用 API 给的 bands，拿不到再推断
        val bandIntFromApi = if (Build.VERSION.SDK_INT >= 30) id?.bands?.firstOrNull() else null
        val bandInt = bandIntFromApi ?: nrGuessBandByDlMhzCN(dlMhz)

        val band = bandInt?.toString() ?: "-"
        val duplex = bandInt?.let { nrBandToDuplexCN(it) } ?: "-"


        val freqDl = dlMhz?.let { formatMhz(it) } ?: "-"

        val ulMhz = if (duplex == "FDD" && dlMhz != null && bandInt != null) {
            val spacing = nrFddDlUlSpacingMhzCN(bandInt)
            if (spacing != null) dlMhz - spacing else null
        } else null
        val freqUl = ulMhz?.let { formatMhz(it) } ?: "-"

        val ssRsrp = ss?.ssRsrp?.takeIf { it != CellInfo.UNAVAILABLE }?.let { "$it dBm" } ?: "-"
        val ssRsrq = ss?.ssRsrq?.takeIf { it != CellInfo.UNAVAILABLE }?.let { "$it dB" } ?: "-"
        val ssSinr = ss?.ssSinr?.takeIf { it != CellInfo.UNAVAILABLE }?.let { "$it dB" } ?: "-"

        //让我来看看NSA 给不给SS-SINR三件套
        //Log.d("NR_SS_DBG", "ssRsrp=${ss?.ssRsrp} ssRsrq=${ss?.ssRsrq} ssSinr=${ss?.ssSinr}")


        return base.copy(
            cellType = "NR",
            duplex = duplex,
            tac = tac,
            pci = pci,
            ci = nci,
            arfcn = arfcn,
            band = band,
            freqDl = freqDl,
            freqUl = freqUl,

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

            lastError = null
        )
    }
}
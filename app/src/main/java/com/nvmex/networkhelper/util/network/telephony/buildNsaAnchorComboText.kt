package com.nvmex.networkhelper.util.network.telephony

import android.os.Build
import android.telephony.CellIdentityNr
import android.telephony.CellInfo
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import com.nvmex.networkhelper.util.network.utils.lteBandToDuplexCN
import com.nvmex.networkhelper.util.network.utils.nrArfcnToMhz
import com.nvmex.networkhelper.util.network.utils.nrBandToDuplexCN

//NSA 时提取 LTE+NR 的 band/duplex
data class BandDuplex(val bandText: String, val duplex: String) {
    fun toDisplay(): String = when {
        bandText == "-" && duplex == "-" -> "-"
        duplex == "-" -> bandText
        bandText == "-" -> "-"
        else -> "$bandText $duplex"
    }
}

fun buildNsaAnchorComboText(
    cellInfos: List<CellInfo>,
    nrDlMhzFallback: ((Int) -> Double?)? = null // 可选：用 nrarfcn->mhz 推 band
): String {
    val lte = cellInfos.filterIsInstance<CellInfoLte>()
        .firstOrNull { it.cellConnectionStatus == CellInfo.CONNECTION_PRIMARY_SERVING }
        ?: cellInfos.filterIsInstance<CellInfoLte>().firstOrNull { it.isRegistered }
        ?: cellInfos.filterIsInstance<CellInfoLte>().firstOrNull()

    val nr = cellInfos.filterIsInstance<CellInfoNr>()
        .firstOrNull { it.cellConnectionStatus == CellInfo.CONNECTION_SECONDARY_SERVING }
        ?: cellInfos.filterIsInstance<CellInfoNr>().firstOrNull { it.isRegistered }
        ?: cellInfos.filterIsInstance<CellInfoNr>().firstOrNull()

    val ltePart = lte?.let { extractLteBandDuplex(it) }
    val nrPart = nr?.let { extractNrBandDuplex(it) }

    val left = ltePart?.toDisplay()?.takeIf { it != "-" }
    val right = nrPart?.toDisplay()?.takeIf { it != "-" }

    return when {
        left != null && right != null -> "$left + $right"
        left != null -> left
        right != null -> right
        else -> "-"
    }
}

private fun extractLteBandDuplex(lte: CellInfoLte): BandDuplex {
    val id = lte.cellIdentity
    val bandInt = if (Build.VERSION.SDK_INT >= 30) id.bands.firstOrNull() else null
    val bandText = bandInt?.let { "B$it" } ?: "-"
    val duplex = bandInt?.let { lteBandToDuplexCN(it) } ?: "-"
    return BandDuplex(bandText, duplex)
}

private fun extractNrBandDuplex(nr: CellInfoNr): BandDuplex {
    val id = nr.cellIdentity as? CellIdentityNr
    val bandFromApi = if (Build.VERSION.SDK_INT >= 30) id?.bands?.firstOrNull() else null

    val arfcn = id?.nrarfcn?.takeIf { it != CellInfo.UNAVAILABLE }
    val dlMhz = arfcn?.let { nrArfcnToMhz(it) }

    val bandInt = bandFromApi ?: nrGuessBandByDlMhzCN(dlMhz)

    val bandText = bandInt?.let { "N$it" } ?: "-"
    val duplex = bandInt?.let { nrBandToDuplexCN(it) } ?: "-"
    return BandDuplex(bandText, duplex)
}

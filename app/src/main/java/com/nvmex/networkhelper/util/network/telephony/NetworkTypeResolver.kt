package com.nvmex.networkhelper.util.network.telephony

import android.os.Build
import android.telephony.ServiceState
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager

data class ResolvedNetwork(
    val dataType: Int,
    val dataTypeName: String,
    val overrideType: Int?,
    val overrideTypeName: String,
    val isNsa: Boolean,
    val nrMode: String,
    val displayTypeName: String
)

class NetworkTypeResolver {

    fun resolve(
        tm: TelephonyManager,
        ss: ServiceState?,
        overrideType: Int?,
        networkTypeOverride: Int? = null
    ): ResolvedNetwork {
        val ssDataType = serviceStateDataNetworkTypeCompat(ss)
        val dataType = networkTypeOverride ?: ssDataType ?: tm.dataNetworkType
        val dataTypeName = networkTypeToName(dataType)

        val (isNsa, nrMode) = computeNrMode(dataType, overrideType)

        // 顶部展示策略：NSA 默认显示 NR
        val displayTypeName = if (isNsa) "NR" else dataTypeName

        return ResolvedNetwork(
            dataType = dataType,
            dataTypeName = dataTypeName,
            overrideType = overrideType,
            overrideTypeName = overrideNetworkTypeToName(overrideType),
            isNsa = isNsa,
            nrMode = nrMode,
            displayTypeName = displayTypeName
        )
    }

    private fun computeNrMode(dataType: Int, overrideType: Int?): Pair<Boolean, String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            val sa = if (dataType == TelephonyManager.NETWORK_TYPE_NR) "SA" else "-"
            return false to sa
        }

        val nsa = TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA
        val adv = TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED

        val isNsa = (overrideType == nsa || overrideType == adv)
        val mode = when {
            isNsa && overrideType == adv -> "NSA-ADV"
            isNsa -> "NSA"
            dataType == TelephonyManager.NETWORK_TYPE_NR -> "SA"
            else -> "-"
        }
        return isNsa to mode
    }

    private fun networkTypeToName(type: Int): String = when (type) {
        TelephonyManager.NETWORK_TYPE_UNKNOWN -> "UNKNOWN"
        TelephonyManager.NETWORK_TYPE_GPRS -> "GPRS"
        TelephonyManager.NETWORK_TYPE_EDGE -> "EDGE"
        TelephonyManager.NETWORK_TYPE_UMTS -> "UMTS"
        TelephonyManager.NETWORK_TYPE_CDMA -> "CDMA"
        TelephonyManager.NETWORK_TYPE_EVDO_0 -> "EVDO_0"
        TelephonyManager.NETWORK_TYPE_EVDO_A -> "EVDO_A"
        TelephonyManager.NETWORK_TYPE_1xRTT -> "1xRTT"
        TelephonyManager.NETWORK_TYPE_HSDPA -> "HSDPA"
        TelephonyManager.NETWORK_TYPE_HSUPA -> "HSUPA"
        TelephonyManager.NETWORK_TYPE_HSPA -> "HSPA"
        TelephonyManager.NETWORK_TYPE_IDEN -> "IDEN"
        TelephonyManager.NETWORK_TYPE_EVDO_B -> "EVDO_B"
        TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
        TelephonyManager.NETWORK_TYPE_EHRPD -> "EHRPD"
        TelephonyManager.NETWORK_TYPE_HSPAP -> "HSPAP"
        TelephonyManager.NETWORK_TYPE_GSM -> "GSM"
        TelephonyManager.NETWORK_TYPE_TD_SCDMA -> "TD_SCDMA"
        TelephonyManager.NETWORK_TYPE_IWLAN -> "IWLAN"
        TelephonyManager.NETWORK_TYPE_NR -> "NR"
        else -> type.toString()
    }

    private fun overrideNetworkTypeToName(type: Int?): String {
        if (type == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return "-"
        return when (type) {
            TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NONE -> "NONE"
            TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_CA -> "LTE_CA"
            TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_ADVANCED_PRO -> "LTE_ADVANCED_PRO"
            TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA -> "NR_NSA"
            TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA_MMWAVE -> "NR_NSA_MMWAVE"
            TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED -> "NR_ADVANCED"
            else -> type.toString()
        }
    }

    private fun serviceStateDataNetworkTypeCompat(ss: ServiceState?): Int? {
        if (ss == null) return null
        return runCatching {
            val m = ss.javaClass.methods.firstOrNull {
                it.name == "getDataNetworkType" && it.parameterTypes.isEmpty()
            }
            (m?.invoke(ss) as? Int)
        }.getOrNull()
    }
}

package com.nvmex.networkhelper.model.network

import android.telephony.TelephonyManager

/** QoS parameters assigned by the mobile core network to a data session. */
data class ModemQosInfo(
    val subId: Int,
    val slotIndex: Int?,
    val sessionKind: SessionKind,
    val dnn: String?,
    val logCode: Int?,
    val qci: Int?,
    val fiveQi: Int?,
    val qfi: Int?,
    val downlinkAmbrKbps: Long?,
    val uplinkAmbrKbps: Long?,
    val source: Source,
    val updatedAt: Long = System.currentTimeMillis(),
    val cached: Boolean = false
) {
    enum class Source {
        NR_5GSM, LTE_ESM;

        val generation: String get() = if (this == NR_5GSM) "5G" else "4G"

        companion object {
            fun forNetwork(networkType: Int?, nrMode: String = "", networkName: String = ""): Source? = when {
                // NSA still uses an EPS/LTE data bearer, not a 5GSM PDU session.
                nrMode.startsWith("NSA", ignoreCase = true) -> LTE_ESM
                networkType == TelephonyManager.NETWORK_TYPE_NR -> NR_5GSM
                networkType == TelephonyManager.NETWORK_TYPE_LTE || networkType == 19 -> LTE_ESM
                networkType == null && networkName.equals("NR", true) -> NR_5GSM
                networkType == null && networkName.equals("LTE", true) -> LTE_ESM
                else -> null
            }
        }
    }
    enum class SessionKind { IMS, DATA }
}

data class ModemQosSessions(
    val lteIms: ModemQosInfo? = null,
    val nrIms: ModemQosInfo? = null,
    val lteData: ModemQosInfo? = null,
    val nrData: ModemQosInfo? = null
) {
    fun imsFor(source: ModemQosInfo.Source?): ModemQosInfo? = when (source) {
        ModemQosInfo.Source.LTE_ESM -> lteIms
        ModemQosInfo.Source.NR_5GSM -> nrIms
        null -> null
    }

    fun dataFor(source: ModemQosInfo.Source?): ModemQosInfo? = when (source) {
        ModemQosInfo.Source.LTE_ESM -> lteData
        ModemQosInfo.Source.NR_5GSM -> nrData
        null -> null
    }

    fun withInfo(info: ModemQosInfo): ModemQosSessions = when {
        info.sessionKind == ModemQosInfo.SessionKind.IMS && info.source == ModemQosInfo.Source.LTE_ESM -> copy(lteIms = info)
        info.sessionKind == ModemQosInfo.SessionKind.IMS -> copy(nrIms = info)
        info.source == ModemQosInfo.Source.LTE_ESM -> copy(lteData = info)
        else -> copy(nrData = info)
    }
}

data class ModemMmInfo(
    val subId: Int,
    val slotIndex: Int?,
    val mmState: Int,
    val mmSubState: Int,
    val fiveGTmsi: String?,
    val logCode: Int = 0xb80c,
    val updatedAt: Long = System.currentTimeMillis()
)

data class ModemQosUiState(
    val valuesBySubId: Map<Int, ModemQosSessions> = emptyMap(),
    val mmBySubId: Map<Int, ModemMmInfo> = emptyMap(),
    val collectorReady: Boolean = false,
    val refreshing: Boolean = false,
    val refreshingSubId: Int? = null,
    val cleaning: Boolean = false,
    val lastNotice: String? = null,
    val noticeBySubId: Map<Int, String> = emptyMap(),
    val cleanupOutput: String? = null,
    val lastError: String? = null,
    val errorBySubId: Map<Int, String> = emptyMap()
)

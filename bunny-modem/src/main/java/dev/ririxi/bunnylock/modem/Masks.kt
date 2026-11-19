package dev.ririxi.bunnylock.modem

import android.telephony.TelephonyManager

object Masks {
    // Network type n is bit n - 1 of an allowed-network-types bitmask.
    private fun maskOf(vararg networkTypes: Int) = networkTypes.fold(0L) { mask, type -> mask or (1L shl (type - 1)) }

    val G2 = maskOf(
        TelephonyManager.NETWORK_TYPE_GSM,
        TelephonyManager.NETWORK_TYPE_GPRS,
        TelephonyManager.NETWORK_TYPE_EDGE
    )

    // The framework deprecated the CDMA types, but the Auto and No 5G masks still carry them for phones that have CDMA.
    @Suppress("DEPRECATION")
    val CDMA =
        maskOf(
            TelephonyManager.NETWORK_TYPE_CDMA,
            TelephonyManager.NETWORK_TYPE_1xRTT,
            TelephonyManager.NETWORK_TYPE_EVDO_0,
            TelephonyManager.NETWORK_TYPE_EVDO_A,
            TelephonyManager.NETWORK_TYPE_EVDO_B,
            TelephonyManager.NETWORK_TYPE_EHRPD,
        )

    val G3 =
        maskOf(
            TelephonyManager.NETWORK_TYPE_UMTS,
            TelephonyManager.NETWORK_TYPE_HSDPA,
            TelephonyManager.NETWORK_TYPE_HSUPA,
            TelephonyManager.NETWORK_TYPE_HSPA,
            TelephonyManager.NETWORK_TYPE_HSPAP,
            TelephonyManager.NETWORK_TYPE_TD_SCDMA,
        )
    val G4 = maskOf(TelephonyManager.NETWORK_TYPE_LTE)
    val G5 = maskOf(TelephonyManager.NETWORK_TYPE_NR)
    val GENERATIONS = G2 or G3 or G4 or G5

    // TelephonyManager.NETWORK_TYPE_LTE_CA, which the SDK hides.
    private const val NETWORK_TYPE_LTE_CA = 19

    // LTE also fits a 5G mask, since it anchors 5G NSA.
    fun allows(
        mask: Long,
        networkType: Int,
    ): Boolean {
        val type = if (networkType == NETWORK_TYPE_LTE_CA) TelephonyManager.NETWORK_TYPE_LTE else networkType
        val anchorsNr = type == TelephonyManager.NETWORK_TYPE_LTE && mask and G5 != 0L
        return mask and maskOf(type) != 0L || anchorsNr
    }
}

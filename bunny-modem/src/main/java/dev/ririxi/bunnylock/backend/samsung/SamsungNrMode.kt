package dev.ririxi.bunnylock.backend.samsung

import android.telephony.SubscriptionManager
import dev.ririxi.bunnylock.backend.ModemSession
import dev.ririxi.bunnylock.modem.NrMode
import dev.ririxi.bunnylock.modem.runSuspendCatching

// TelephonyManager.SEM_NR_MODE_* values. 3 and 4 are temporary IMS states, and -1 is a failed read.
private val SEM_NR_MODES = mapOf(NrMode.BOTH to 0, NrMode.NSA to 1, NrMode.SA to 2)

// Works without root and on any chipset, so both the Qualcomm and the radio HAL backends use it.
internal class SamsungNrMode(
    private val session: ModemSession,
) {
    val available get() = session.current.device?.samsungNrMode == true

    suspend fun read(sim: Int): NrMode? {
        if (!available) return null
        val mode =
            runSuspendCatching {
                session.call("samsung.nrMode", sim = sim) { put("dataSubId", SubscriptionManager.getDefaultDataSubscriptionId()) }.optInt("mode", -1)
            }.getOrNull()
        return SEM_NR_MODES.entries.firstOrNull { it.value == mode }?.key
    }

    suspend fun set(mode: NrMode) {
        session.call("samsung.setNrMode") {
            put("dataSubId", SubscriptionManager.getDefaultDataSubscriptionId())
            put("mode", SEM_NR_MODES.getValue(mode))
        }
    }
}

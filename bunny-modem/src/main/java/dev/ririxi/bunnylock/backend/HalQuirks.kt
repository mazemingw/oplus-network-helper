package dev.ririxi.bunnylock.backend

import dev.ririxi.bunnylock.core.Ran
import dev.ririxi.bunnylock.modem.DeviceInfo

internal class HalQuirks(
    private val session: ModemSession,
) {
    private val prefs get() = session.prefs
    private val device get() = session.current.device

    var needsChannels: Boolean
        get() = needsChannels(device)
        set(value) {
            prefs.edit().putBoolean(modelKey("halChannels", device), value).apply()
            session.update { copy(halNeedsChannels = value) }
        }

    var clearsLeftOutNetworks: Boolean
        get() = prefs.getBoolean(modelKey("halClearsLeftOut", device), false)
        set(value) = prefs.edit().putBoolean(modelKey("halClearsLeftOut", device), value).apply()

    fun needsChannels(device: DeviceInfo?): Boolean = prefs.getBoolean(modelKey("halChannels", device), false)

    fun firstFillAttempt(fill: Set<Int>): Int = prefs.getInt(firstFillAttemptKey(fill), 0)

    fun rememberFirstFillAttempt(
        fill: Set<Int>,
        attempt: Int,
    ) {
        if (Ran.EUTRAN !in fill && Ran.NGRAN !in fill) return
        prefs.edit().putInt(firstFillAttemptKey(fill), attempt).apply()
    }

    fun diagnostics(): String {
        val fills = mapOf("lte" to setOf(Ran.EUTRAN), "nr" to setOf(Ran.NGRAN), "lte+nr" to setOf(Ran.EUTRAN, Ran.NGRAN))
        val firstFillAttempts = fills.entries.joinToString { (name, fill) -> "$name=${firstFillAttempt(fill)}" }
        return "HAL quirks: needs channels: $needsChannels, names every network: $clearsLeftOutNetworks, first fill attempt: $firstFillAttempts"
    }

    private fun modelKey(
        name: String,
        device: DeviceInfo?,
    ) = "$name.${device?.manufacturer}.${device?.model}"

    // A refusal caused by the LTE list says nothing about the 5G list, so each mix of filled networks has its own start.
    private fun firstFillAttemptKey(fill: Set<Int>): String {
        val filled = listOfNotNull("lte".takeIf { Ran.EUTRAN in fill }, "nr".takeIf { Ran.NGRAN in fill }).joinToString("+")
        return "${modelKey("halFirstFillAttempt", device)}.$filled"
    }
}

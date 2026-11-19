package dev.ririxi.bunnylock.backend.unisoc

import dev.ririxi.bunnylock.backend.BackendProfile
import dev.ririxi.bunnylock.backend.BackendReadout
import dev.ririxi.bunnylock.backend.HalLocks
import dev.ririxi.bunnylock.backend.ModemBackend
import dev.ririxi.bunnylock.backend.ModemSession
import dev.ririxi.bunnylock.backend.bandGroupState
import dev.ririxi.bunnylock.backend.bandsToSend
import dev.ririxi.bunnylock.backend.has5g
import dev.ririxi.bunnylock.core.Ran
import dev.ririxi.bunnylock.core.intList
import dev.ririxi.bunnylock.modem.BackendFeatures
import dev.ririxi.bunnylock.modem.Chipset
import dev.ririxi.bunnylock.modem.DeviceInfo
import dev.ririxi.bunnylock.modem.Group
import dev.ririxi.bunnylock.modem.ModemState
import dev.ririxi.bunnylock.modem.R
import dev.ririxi.bunnylock.modem.Text
import dev.ririxi.bunnylock.modem.followedBy
import dev.ririxi.bunnylock.modem.optText
import dev.ririxi.bunnylock.modem.runSuspendCatching
import dev.ririxi.bunnylock.root.fail
import org.json.JSONArray
import org.json.JSONObject
import kotlin.time.Duration.Companion.seconds

internal class UnisocBackend(
    session: ModemSession,
    hal: HalLocks,
) : ModemBackend(session, hal) {
    private val current get() = session.current

    override suspend fun read(
        sim: Int,
        aospState: JSONObject?,
    ): BackendReadout {
        val state = session.call("unisoc.state", sim = sim) { put("nr", has5g(aospState)) }
        nrError = state.optText("nrError")
        val settable = state.intList("lteSettable").orEmpty()
        val lteSupported = (unlockedLteBands ?: Group.LTE.defaults()).filter { it in settable }
        val groups = mutableListOf(bandGroupState(Group.LTE, lteSupported, state.intList("lte")?.ifEmpty { null }))
        state.intList("nrSupported")?.takeIf { it.isNotEmpty() }?.let { groups += bandGroupState(Group.NR, it, state.intList("nr")?.ifEmpty { null }) }
        val specs = hal.channelSpecs(aospState, sim)
        return BackendReadout(
            groups = groups,
            lteLock = hal.channelLock(specs, Ran.EUTRAN),
            nrLock = hal.channelLock(specs, Ran.NGRAN),
            cellLocksThroughHal = true,
        )
    }

    override suspend fun setBands(
        group: Group,
        bands: List<Int>,
        liftsLock: Boolean,
        done: Text,
    ): Text {
        val key = if (group.isNr) "nr" else "lte"
        session.call("unisoc.setBands", 40.seconds) { put(key, JSONArray(bandsToSend(bands, liftsLock))) }
        val held = restartAndRead(nr = group.isNr)?.intList(key)
        if (liftsLock && !group.isNr) rememberUnlockedLte(held)
        if (!liftsLock && held !== null && (held.isEmpty() || !bands.containsAll(held))) fail(R.string.unisoc_bands_kept)
        return done.followedBy(Text(R.string.modem_note_radio_restarted))
    }

    override suspend fun unlockAll(): Text? {
        // Without a readout there's no telling, so the masks are cleared anyway.
        val bandsLocked = current.groups.isEmpty() || current.groups.any { it.locked }
        if (bandsLocked) {
            session.call("unisoc.setBands", 40.seconds) {
                put("lte", JSONArray())
                if (current.groups.any { it.group.isNr }) put("nr", JSONArray())
            }
        }
        val cellUnlock = hal.liftCellLocks()
        if (bandsLocked) rememberUnlockedLte(restartAndRead(nr = false)?.intList("lte"))
        return cellUnlock
    }

    // The modem only applies a band mask when its radio restarts.
    private suspend fun restartAndRead(nr: Boolean): JSONObject? {
        session.restartRadio()
        return runSuspendCatching { session.call("unisoc.state") { put("nr", nr) } }.getOrNull()
    }

    private fun rememberUnlockedLte(bands: List<Int>?) {
        bands?.takeIf { it.isNotEmpty() }?.let { unlockedLteBands = it }
    }

    private var nrError: Text? = null

    override fun diagnostics() =
        listOfNotNull(
            "Unisoc LTE bands when unlocked: ${unlockedLteBands?.joinToString(", ") ?: "not seen yet"}",
            nrError?.let { "Unisoc 5G bands couldn't be read: ${session.inEnglish(it)}" },
        )

    // Some modems report every LTE band they have when unlocked, not an empty mask. Those bands are kept to tell that from a lock.
    private var unlockedLteBands: List<Int>?
        get() = session.prefs.getString(unlockedLteBandsKey(), null)?.split(',')?.mapNotNull { it.toIntOrNull() }?.ifEmpty { null }
        set(value) = session.prefs.edit().putString(unlockedLteBandsKey(), value?.joinToString(",")).apply()

    private fun unlockedLteBandsKey() = "unisocUnlockedLte.${current.device?.manufacturer}.${current.device?.model}"

    companion object : BackendProfile {
        override val chipset = Chipset.UNISOC

        override fun suits(device: DeviceInfo) = device.chipset == Chipset.UNISOC && device.rilPassthrough

        // Its commands only go through the RIL passthrough, never a tty.
        override fun reaches(
            device: DeviceInfo,
            shellOnly: Boolean,
        ) = device.rilPassthrough

        override fun features(state: ModemState) =
            BackendFeatures(
                cellHint = Text(R.string.cell_hint_hal),
                plmnHint = Text(R.string.cell_plmn_hint_android),
            )
    }
}

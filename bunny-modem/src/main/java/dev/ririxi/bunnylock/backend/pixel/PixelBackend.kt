package dev.ririxi.bunnylock.backend.pixel

import dev.ririxi.bunnylock.backend.BackendProfile
import dev.ririxi.bunnylock.backend.BackendReadout
import dev.ririxi.bunnylock.backend.HalLocks
import dev.ririxi.bunnylock.backend.ModemBackend
import dev.ririxi.bunnylock.backend.ModemSession
import dev.ririxi.bunnylock.backend.bandGroupState
import dev.ririxi.bunnylock.backend.bandsToSend
import dev.ririxi.bunnylock.core.Ran
import dev.ririxi.bunnylock.core.intList
import dev.ririxi.bunnylock.modem.BackendFeatures
import dev.ririxi.bunnylock.modem.Chipset
import dev.ririxi.bunnylock.modem.DeviceInfo
import dev.ririxi.bunnylock.modem.Group
import dev.ririxi.bunnylock.modem.ModemState
import dev.ririxi.bunnylock.modem.NrMode
import dev.ririxi.bunnylock.modem.R
import dev.ririxi.bunnylock.modem.Text
import dev.ririxi.bunnylock.modem.followedBy
import org.json.JSONArray
import org.json.JSONObject

private val NR_MODE_VALUES = mapOf(NrMode.BOTH to "11", NrMode.NSA to "01", NrMode.SA to "10")

internal class PixelBackend(
    session: ModemSession,
    hal: HalLocks,
) : ModemBackend(session, hal) {
    override suspend fun read(
        sim: Int,
        aospState: JSONObject?,
    ): BackendReadout {
        val pixelState = session.call("pixel.state", sim = sim)
        val specs = hal.channelSpecs(aospState, sim)
        val lteBands = if (pixelState.optBoolean("lteLocked")) pixelState.intList("lte") else null
        val nrBands = if (pixelState.optBoolean("nrLocked")) pixelState.intList("nr") else null
        return BackendReadout(
            groups =
                listOf(
                    bandGroupState(Group.LTE, supportedBands = null, currentBands = lteBands),
                    bandGroupState(Group.NR, supportedBands = null, currentBands = nrBands),
                ),
            nrMode = NR_MODE_VALUES.entries.firstOrNull { it.value == pixelState.optString("nrMode") }?.key,
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
        session.call(if (group == Group.LTE) "pixel.setLte" else "pixel.setNr") { put("bands", JSONArray(bandsToSend(bands, liftsLock))) }
        session.restartRadio()
        return done.followedBy(Text(R.string.modem_note_radio_restarted))
    }

    override suspend fun setNrMode(mode: NrMode) {
        session.call("pixel.setNrMode") { put("value", NR_MODE_VALUES.getValue(mode)) }
        session.restartRadio()
    }

    override suspend fun unlockAll(): Text? {
        session.call("pixel.setLte") { put("bands", JSONArray()) }
        session.call("pixel.setNr") { put("bands", JSONArray()) }
        hal.clear()
        session.restartRadio()
        return null
    }

    companion object : BackendProfile {
        override val chipset = Chipset.TENSOR

        override fun suits(device: DeviceInfo) = device.chipset == Chipset.TENSOR && device.pixelRouter

        override fun reaches(
            device: DeviceInfo,
            shellOnly: Boolean,
        ) = !shellOnly

        override fun features(state: ModemState) =
            BackendFeatures(
                cellHint = Text(R.string.cell_hint_hal),
                plmnHint = Text(R.string.cell_plmn_hint_android),
                nrMode = true,
            )
    }
}

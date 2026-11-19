package dev.ririxi.bunnylock.backend.hal

import dev.ririxi.bunnylock.backend.BackendProfile
import dev.ririxi.bunnylock.backend.BackendReadout
import dev.ririxi.bunnylock.backend.HalLocks
import dev.ririxi.bunnylock.backend.LocksBandsTogether
import dev.ririxi.bunnylock.backend.ModemBackend
import dev.ririxi.bunnylock.backend.ModemSession
import dev.ririxi.bunnylock.backend.bandGroupState
import dev.ririxi.bunnylock.backend.has5g
import dev.ririxi.bunnylock.backend.samsung.SamsungNrMode
import dev.ririxi.bunnylock.backend.supportsNetwork
import dev.ririxi.bunnylock.core.Bands
import dev.ririxi.bunnylock.core.Ran
import dev.ririxi.bunnylock.modem.BackendFeatures
import dev.ririxi.bunnylock.modem.Chipset
import dev.ririxi.bunnylock.modem.DeviceInfo
import dev.ririxi.bunnylock.modem.Group
import dev.ririxi.bunnylock.modem.Masks
import dev.ririxi.bunnylock.modem.ModemState
import dev.ririxi.bunnylock.modem.NrMode
import dev.ririxi.bunnylock.modem.R
import dev.ririxi.bunnylock.modem.Text
import org.json.JSONObject

// AccessNetworkConstants.UtranBand has no bands 15 to 18.
private val HAL_WCDMA_BANDS = Bands.WCDMA.filter { it !in 15..18 }

internal class HalBackend(
    session: ModemSession,
    hal: HalLocks,
    private val samsung: SamsungNrMode,
) : ModemBackend(session, hal),
    LocksBandsTogether {
    override suspend fun read(
        sim: Int,
        aospState: JSONObject?,
    ): BackendReadout {
        val specs = hal.channelSpecs(aospState, sim)
        val nrBands = hal.channelBands(specs, Ran.NGRAN)
        val groups = mutableListOf(bandGroupState(Group.LTE, supportedBands = null, currentBands = hal.channelBands(specs, Ran.EUTRAN)))
        if (has5g(aospState) || nrBands !== null) groups += bandGroupState(Group.NR, supportedBands = null, currentBands = nrBands)
        val wcdmaBands = hal.channelBands(specs, Ran.UTRAN)
        if (supportsNetwork(aospState, Masks.G3) || wcdmaBands !== null) groups += bandGroupState(Group.WCDMA, supportedBands = HAL_WCDMA_BANDS, currentBands = wcdmaBands)
        val gsmBands = hal.channelBands(specs, Ran.GERAN)?.let(hal::gsmBandsOf)
        if (supportsNetwork(aospState, Masks.G2) || gsmBands !== null) groups += bandGroupState(Group.GSM, supportedBands = null, currentBands = gsmBands)
        return BackendReadout(
            nrMode = samsung.read(sim),
            groups = groups,
            lteLock = hal.channelLock(specs, Ran.EUTRAN),
            nrLock = hal.channelLock(specs, Ran.NGRAN),
            bandLocksThroughHal = true,
            cellLocksThroughHal = true,
        )
    }

    override suspend fun setBands(
        group: Group,
        bands: List<Int>,
        liftsLock: Boolean,
        done: Text,
    ): Text? = hal.setBands(group, bands, liftsLock)

    override suspend fun lockBands(locks: Map<Group, List<Int>>): Text? = hal.lockBands(locks)

    override suspend fun setNrMode(mode: NrMode) {
        if (!samsung.available) return super.setNrMode(mode)
        samsung.set(mode)
    }

    // Samsung's RIL stops following radio HAL locks once the network mode changes.
    override suspend fun keepLocksAfterModeChange() = hal.resend()

    override suspend fun unlockAll(): Text? = hal.unlockAll()

    companion object : BackendProfile {
        override val chipset: Chipset? = null

        override fun suits(device: DeviceInfo) = true

        override fun reaches(
            device: DeviceInfo,
            shellOnly: Boolean,
        ) = true

        override fun features(state: ModemState) =
            BackendFeatures(
                cellHint = Text(R.string.cell_hint_hal),
                plmnHint = Text(R.string.cell_plmn_hint_android),
                nrMode = state.device?.samsungNrMode == true,
                forgetNeedsChannels = state.halNeedsChannels,
            )
    }
}

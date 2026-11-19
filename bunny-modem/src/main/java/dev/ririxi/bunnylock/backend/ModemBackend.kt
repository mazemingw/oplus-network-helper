package dev.ririxi.bunnylock.backend

import dev.ririxi.bunnylock.data.Carrier
import dev.ririxi.bunnylock.data.Cell
import dev.ririxi.bunnylock.modem.BackendFeatures
import dev.ririxi.bunnylock.modem.BandGroupState
import dev.ririxi.bunnylock.modem.Chipset
import dev.ririxi.bunnylock.modem.DeviceInfo
import dev.ririxi.bunnylock.modem.Group
import dev.ririxi.bunnylock.modem.Masks
import dev.ririxi.bunnylock.modem.ModemState
import dev.ririxi.bunnylock.modem.NrMode
import dev.ririxi.bunnylock.modem.R
import dev.ririxi.bunnylock.modem.Text
import dev.ririxi.bunnylock.modem.optText
import dev.ririxi.bunnylock.root.fail
import org.json.JSONObject

internal data class BackendReadout(
    val groups: List<BandGroupState> = emptyList(),
    val nrMode: NrMode? = null,
    val lteLock: String? = null,
    val nrLock: String? = null,
    val legacyCellLock: String? = null,
    val strayChannel: String? = null,
    val bandLocksThroughHal: Boolean = false,
    val cellLocksThroughHal: Boolean = false,
    val plmnLock: String? = null,
    val plmnReported: Boolean = false,
    val cells: List<Cell> = emptyList(),
    val carriers: List<Carrier> = emptyList(),
    val allowedMask: Long? = null,
    val carrierAggregation: Boolean? = null,
)

internal fun ModemState.withReadout(readout: BackendReadout): ModemState =
    copy(
        groups = readout.groups,
        nrMode = readout.nrMode,
        lteLock = readout.lteLock,
        nrLock = readout.nrLock,
        legacyCellLock = readout.legacyCellLock,
        strayChannel = readout.strayChannel,
        bandLocksThroughHal = readout.bandLocksThroughHal,
        cellLocksThroughHal = readout.cellLocksThroughHal,
        plmnLock = readout.plmnLock,
        carrierAggregation = readout.carrierAggregation,
    )

// earfcn and arfcn only count when their network is being locked.
internal data class CellLock(
    val lte: Boolean,
    val earfcn: Long,
    val ltePcis: List<Int>,
    val nr: Boolean,
    val arfcn: Long,
    val nrPcis: List<Int>,
    val nrBand: Int?,
    val nrScs: Int,
) {
    val pcisGiven get() = ltePcis.isNotEmpty() || nrPcis.isNotEmpty()
}

internal class NetModeResult(
    val throughAndroid: Boolean,
    val airplaneMode: Boolean = false,
    val heldBack: Text? = null,
    val note: Text? = null,
) {
    companion object {
        fun fromAndroid(reply: JSONObject) = NetModeResult(throughAndroid = true, airplaneMode = reply.optBoolean("airplaneMode"), heldBack = reply.optText("heldBack"))
    }
}

internal interface BackendProfile {
    val chipset: Chipset?

    // Whether bunnylock picks this backend on its own, as opposed to one the user may pick.
    fun suits(device: DeviceInfo): Boolean

    // shellOnly is Shizuku with ADB access, which reaches telephony services but not the modem.
    fun reaches(
        device: DeviceInfo,
        shellOnly: Boolean,
    ): Boolean

    fun features(state: ModemState): BackendFeatures
}

// Every list narrows its network's bands.
internal interface LocksBandsTogether {
    suspend fun lockBands(locks: Map<Group, List<Int>>): Text?
}

internal abstract class ModemBackend(
    protected val session: ModemSession,
    protected val hal: HalLocks,
) {
    abstract suspend fun read(
        sim: Int,
        aospState: JSONObject?,
    ): BackendReadout

    // Returns what the user is told instead of done, or null for done itself.
    abstract suspend fun setBands(
        group: Group,
        bands: List<Int>,
        liftsLock: Boolean,
        done: Text,
    ): Text?

    abstract suspend fun unlockAll(): Text?

    open suspend fun setNetworkMode(
        mask: Long,
        throughAndroid: suspend () -> JSONObject,
    ): NetModeResult = NetModeResult.fromAndroid(throughAndroid())

    open suspend fun keepLocksAfterModeChange() {}

    open suspend fun setNrMode(mode: NrMode) {
        val backend = session.current.backend ?: fail(R.string.modem_current_backend_no_nr_mode)
        fail(R.string.modem_backend_no_nr_mode, Text(backend.label))
    }

    open suspend fun setCarrierAggregation(
        enabled: Boolean,
        done: Text,
    ): Text? = fail(R.string.modem_no_ca_control)

    open suspend fun lockCells(lock: CellLock): Text? = hal.lockCells(lock)

    open suspend fun unlockCells(): Text? = hal.unlockCells()

    open suspend fun lockPlmn(
        mcc: String,
        mnc: String,
        throughAndroid: suspend () -> Text,
    ): Text = throughAndroid()

    open suspend fun clearPlmn(throughAndroid: suspend () -> Unit) = throughAndroid()

    open suspend fun resendLocks() = hal.resend()

    open suspend fun refreshLive(sim: Int) {}

    open fun diagnostics(): List<String> = emptyList()

    open fun forgetKeptOffBands() {}

    protected suspend fun resendBandLocks() {
        session.current.groups.filter { it.locked }.forEach {
            setBands(it.group, it.current.orEmpty().sorted(), liftsLock = false, done = Text(R.string.modem_bands_locked, it.group.label))
        }
    }
}

// An empty list lifts the restriction on every backend.
internal fun bandsToSend(
    bands: List<Int>,
    liftsLock: Boolean,
): List<Int> = if (liftsLock) emptyList() else bands

internal fun bandGroupState(
    group: Group,
    supportedBands: List<Int>?,
    currentBands: List<Int>?,
): BandGroupState {
    val knownBands = supportedBands?.takeIf { it.isNotEmpty() } ?: group.defaults()
    val supported = (knownBands + currentBands.orEmpty()).distinct().sorted()
    val current = currentBands?.toSet()
    return BandGroupState(group, supported, current)
}

internal fun has5g(aospState: JSONObject?): Boolean = supportsNetwork(aospState, Masks.G5)

internal fun supportsNetwork(
    aospState: JSONObject?,
    mask: Long,
): Boolean {
    val supported = aospState?.optLong("supportedTypes") ?: 0L
    val unknown = supported and Masks.G4 == 0L
    return unknown || supported and mask != 0L
}

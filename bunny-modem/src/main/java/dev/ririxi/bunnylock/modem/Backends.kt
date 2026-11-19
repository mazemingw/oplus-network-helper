package dev.ririxi.bunnylock.modem

import dev.ririxi.bunnylock.backend.BackendProfile
import dev.ririxi.bunnylock.backend.HalLocks
import dev.ririxi.bunnylock.backend.ModemBackend
import dev.ririxi.bunnylock.backend.ModemSession
import dev.ririxi.bunnylock.backend.hal.HalBackend
import dev.ririxi.bunnylock.backend.mediatek.MediatekBackend
import dev.ririxi.bunnylock.backend.pixel.PixelBackend
import dev.ririxi.bunnylock.backend.qualcomm.QualcommBackend
import dev.ririxi.bunnylock.backend.samsung.SamsungNrMode
import dev.ririxi.bunnylock.backend.unisoc.UnisocBackend

private val Backend.profile: BackendProfile
    get() =
        when (this) {
            Backend.QUALCOMM -> QualcommBackend
            Backend.MEDIATEK -> MediatekBackend
            Backend.PIXEL -> PixelBackend
            Backend.UNISOC -> UnisocBackend
            Backend.ANDROID -> HalBackend
        }

internal class Backends(
    session: ModemSession,
    hal: HalLocks,
) {
    private val samsung = SamsungNrMode(session)
    val qualcomm = QualcommBackend(session, hal, samsung)
    private val mediatek = MediatekBackend(session, hal)
    private val pixel = PixelBackend(session, hal)
    private val unisoc = UnisocBackend(session, hal)
    private val android = HalBackend(session, hal, samsung)

    operator fun get(backend: Backend?): ModemBackend =
        when (backend) {
            Backend.QUALCOMM -> qualcomm
            Backend.MEDIATEK -> mediatek
            Backend.PIXEL -> pixel
            Backend.UNISOC -> unisoc
            Backend.ANDROID, null -> android
        }
}

internal fun offeredBackends(device: DeviceInfo): List<Backend> =
    Backend.entries.filter { it.profile.chipset.let { chipset -> chipset === null || device.chipset == Chipset.UNKNOWN || device.chipset == chipset } }

internal fun availableBackends(
    device: DeviceInfo,
    shellOnly: Boolean,
): Set<Backend> = offeredBackends(device).filter { it.profile.reaches(device, shellOnly) }.toSet()

internal fun suggestedBackend(
    device: DeviceInfo,
    available: Set<Backend>,
): Backend = Backend.entries.first { it in available && it.profile.suits(device) }

internal fun featuresOf(state: ModemState): BackendFeatures = (state.backend ?: Backend.ANDROID).profile.features(state)

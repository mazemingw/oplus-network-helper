package dev.ririxi.bunnylock.backend.samsung

import dev.ririxi.bunnylock.backend.ModemSession
import kotlin.time.Duration.Companion.seconds

internal class SamsungBandMenu(
    private val session: ModemSession,
    private val shizukuLauncher: (suspend () -> Unit)?,
) {
    // Samsung refuses `am start` from the shell user, so with ADB access the app opens the menu itself.
    suspend fun open() {
        if (session.current.limited && shizukuLauncher !== null) shizukuLauncher() else session.call("samsung.menu", 40.seconds)
    }
}

package dev.qcom.efs

/** Monotonic five-second gate; the view model enforces it independently of button state. */
internal class BackupBypassGate(private val now: () -> Long) {
    private var startedAt: Long? = null
    fun begin() { startedAt = now() }
    fun remainingSeconds(): Int {
        val start = startedAt ?: return 5
        return ((5000L - (now() - start).coerceAtLeast(0)).coerceAtLeast(0) + 999).div(1000).toInt()
    }
    fun confirm(): Boolean {
        if (startedAt == null || remainingSeconds() > 0) return false
        startedAt = null
        return true
    }
    fun cancel() { startedAt = null }
}

internal val UiState.backupWriteAuthorized: Boolean get() =
    (initialBackupComplete && cloudBackupComplete) || backupBypassed

internal val UiState.canOfferBackupBypass: Boolean get() = phase == Phase.READY && storageGranted &&
    !busy && !backupRunning && !backupWriteAuthorized && !initialBackupError.isNullOrBlank()

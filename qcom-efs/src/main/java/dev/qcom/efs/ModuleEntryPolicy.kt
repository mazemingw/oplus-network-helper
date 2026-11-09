package dev.qcom.efs

internal enum class ModuleEntryAction { WAIT, CONNECT, RECONNECT, VERIFY }

/** Entry decisions depend on the current session, never on whether a previous entry once connected. */
internal fun moduleEntryAction(phase: Phase, busy: Boolean, backupRunning: Boolean, connected: Boolean) = when {
    busy || backupRunning -> ModuleEntryAction.WAIT
    phase == Phase.DISCONNECTED || phase == Phase.FAILED -> ModuleEntryAction.CONNECT
    phase == Phase.READY && !connected -> ModuleEntryAction.RECONNECT
    phase == Phase.READY -> ModuleEntryAction.VERIFY
    else -> ModuleEntryAction.WAIT
}

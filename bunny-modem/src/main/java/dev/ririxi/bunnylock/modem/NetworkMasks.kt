package dev.ririxi.bunnylock.modem

/** The network mode, or Auto when the phone doesn't report one. */
internal val ModemState.netModeMask: Long
    get() = allowedMask ?: NetMode.AUTO.mask

/** The network mode with only [locking] and the networks that have a band lock of their own left on. */
internal fun ModemState.maskKeepingLocks(locking: Group): Long {
    val kept = groups.filter { it.group == locking || it.locked }.map { it.group.generation }.toSet()
    val dropped = groups.map { it.group.generation }.filter { it !in kept }
    return dropped.fold(netModeMask or locking.generation) { mask, generation -> mask and generation.inv() }
}

/** One name per network in [mask], such as "5G NR". */
internal fun ModemState.networkNames(mask: Long): List<String> =
    groups
        .map { it.group }
        .filter { it.generation and mask != 0L }
        .distinctBy { it.generation }
        .map { it.label }

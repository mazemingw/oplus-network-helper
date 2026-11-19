package dev.ririxi.bunnylock.modem

internal val ModemState.netModeMask: Long
    get() = allowedMask ?: NetMode.AUTO.mask

/** The network mode with only [locking], [alsoKept] and the networks that have a band lock of their own left on. Only [locking] is turned on. */
internal fun ModemState.maskKeepingLocks(
    locking: Collection<Group>,
    alsoKept: Collection<Group> = emptyList(),
): Long {
    val kept = groups.filter { it.group in locking || it.group in alsoKept || it.locked }.map { it.group.generation }.toSet()
    val dropped = groups.map { it.group.generation }.filter { it !in kept }
    val withLocking = locking.fold(netModeMask) { mask, group -> mask or group.generation }
    return dropped.fold(withLocking) { mask, generation -> mask and generation.inv() }
}

/** One name per network in [mask], such as "5G NR". */
internal fun ModemState.networkNames(mask: Long): List<String> =
    groups
        .map { it.group }
        .filter { it.generation and mask != 0L }
        .distinctBy { it.generation }
        .map { it.label }

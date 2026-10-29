package com.nvmex.networkhelper.util.qmi

data class QmiRxSlot(val index: Int, val measurement: QmiRxChain?)

/** Fixed display positions are not a claim that all four antennas are tuned or reported. */
fun qmiRxSlots(chains: List<QmiRxChain>): List<QmiRxSlot> = (0..3).map { index ->
    QmiRxSlot(index, chains.firstOrNull { it.index == index })
}

/** Retain previously visible RAT positions, but never retain their old measurements. */
fun qmiRxDisplayGroups(groups: List<QmiRxGroup>, retainedRats: List<String>): List<QmiRxGroup> =
    QmiRxDecoder.rats.filter { rat -> rat in retainedRats || groups.any { it.rat == rat && it.chains.isNotEmpty() } }
        .map { rat -> groups.firstOrNull { it.rat == rat } ?: QmiRxGroup(rat, emptyList()) }

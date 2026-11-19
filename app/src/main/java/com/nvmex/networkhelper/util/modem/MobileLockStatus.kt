package com.nvmex.networkhelper.util.modem

import android.content.Context
import android.telephony.SubscriptionManager
import com.nvmex.networkhelper.util.qmi.QmiBand
import com.nvmex.networkhelper.util.qmi.QmiDeviceState

internal data class MobileLockTarget(val sim: Int, val subId: Int) {
    fun isActive(context: Context) = this == forSim(context, sim)
    fun isDefaultData(context: Context) = SubscriptionManager.getDefaultDataSubscriptionId() == subId && isActive(context)

    companion object {
        fun forSim(context: Context, sim: Int): MobileLockTarget? = runCatching {
            if (sim !in 1..2) return null
            context.getSystemService(SubscriptionManager::class.java)
                ?.getActiveSubscriptionInfoForSimSlotIndex(sim - 1)?.subscriptionId
                ?.takeIf(SubscriptionManager::isValidSubscriptionId)?.let { MobileLockTarget(sim, it) }
        }.getOrNull()
    }
}

internal enum class MobileLock {
    BANDS, MODE, LTE_CELL, NR_CELL, LEGACY_CELL, PLMN
}

/** Remembers modem readback across navigation and process restarts. Unknown readings leave the last known state intact. */
internal class MobileLockStatus(context: Context) {
    private val prefs = context.getSharedPreferences("mobile_lock_status", Context.MODE_PRIVATE)

    private fun key(target: MobileLockTarget) = "${target.sim}:${target.subId}"

    fun read(target: MobileLockTarget): Set<MobileLock> = prefs.getStringSet(key(target), emptySet()).orEmpty()
        .mapNotNull { name -> MobileLock.entries.find { it.name == name } }.toSet()

    fun record(target: MobileLockTarget, observations: Map<MobileLock, Boolean?>) {
        val previous = read(target)
        val next = MobileLock.entries.filterTo(mutableSetOf()) { observations[it] ?: (it in previous) }
        if (next == previous) return
        val editor = prefs.edit()
        if (next.isEmpty()) editor.remove(key(target)) else editor.putStringSet(key(target), next.map { it.name }.toSet())
        editor.apply()
    }
}

internal val qmiCellLockFamilies = mapOf("lte" to MobileLock.LTE_CELL, "nr" to MobileLock.NR_CELL, "plmn" to MobileLock.PLMN)

internal fun QmiDeviceState.cellLocksToClear(saved: Set<MobileLock>): Set<String> = qmiCellLockFamilies.filter { (family, lock) ->
    locks[family]?.isNotEmpty() ?: (lock in saved)
}.keys

internal fun QmiDeviceState.bandMaskLocked(): Boolean? {
    val supported = hardware.orEmpty().filterValues { it.isNotEmpty() }
    if (!controlReady || supported.isEmpty()) return null
    val observations = supported.map { (band, all) ->
        profile.bands[band]?.takeIf { it.isNotEmpty() }?.let { !it.containsAll(all) }
    }
    return when {
        true in observations -> true
        null in observations -> null
        else -> false
    }
}

internal fun QmiDeviceState.automaticMode(): Boolean? {
    val supported = QmiBand.entries.filter { !hardware?.get(it).isNullOrEmpty() }.map { it.rat }.toSet()
    if (!controlReady || supported.isEmpty()) return null
    return profile.rats == supported && ("nr" !in supported || profile.nrMode in setOf("both", "unknown"))
}

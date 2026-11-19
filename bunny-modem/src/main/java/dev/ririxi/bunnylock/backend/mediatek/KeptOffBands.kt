package dev.ririxi.bunnylock.backend.mediatek

import android.telephony.TelephonyManager
import dev.ririxi.bunnylock.backend.ModemSession
import dev.ririxi.bunnylock.modem.Group

// A modem can keep some of the bands it lists off on one network, and turn some off only a while after an unlock.
// Those bands are kept per SIM and network. After an unlock, each read adds what the modem turned off since, until the app locks the group again.
internal class KeptOffBands(
    private val session: ModemSession,
) {
    private val prefs get() = session.prefs

    fun network(sim: Int): String? {
        val subscriptionId = session.subId(sim)
        val telephony = session.context.getSystemService(TelephonyManager::class.java) ?: return null
        val simTelephony = if (subscriptionId >= 0) telephony.createForSubscriptionId(subscriptionId) else telephony
        return simTelephony.networkOperator?.takeIf { it.length >= 5 }
    }

    fun scope(sim: Int): String? = network(sim)?.let { "sim$sim.$it" }

    fun bands(
        scope: String?,
        group: Group,
    ): Set<Int> {
        if (scope === null) return emptySet()
        return prefs
            .getString(bandsKey(scope, group), null)
            ?.split(',')
            ?.mapNotNull { it.toIntOrNull() }
            ?.toSet()
            .orEmpty()
    }

    fun record(
        scope: String?,
        group: Group,
        keptOff: List<Int>,
    ): List<Int> {
        if (scope === null) return keptOff
        val known = bands(scope, group)
        val editor = prefs.edit()
        if (keptOff.isEmpty()) editor.remove(bandsKey(scope, group)) else editor.putString(bandsKey(scope, group), keptOff.joinToString(","))
        editor.apply()
        return keptOff - known
    }

    fun learn(
        scope: String,
        group: Group,
        keptOff: List<Int>,
    ): List<Int> {
        if (!prefs.getBoolean(learningKey(scope, group), false) || keptOff.toSet() == bands(scope, group)) return emptyList()
        return record(scope, group, keptOff)
    }

    fun startLearning(
        scope: String?,
        group: Group,
    ) {
        if (scope === null) return
        prefs.edit().putBoolean(learningKey(scope, group), true).apply()
    }

    // Every network of the SIM stops learning, so going back to one doesn't take the lock for kept-off bands.
    fun stopLearning(
        sim: Int,
        group: Group,
    ) = removeKeys { it.startsWith("$LEARNING_PREFIX.sim$sim.") && it.endsWith(".${group.key}") }

    fun forget(scope: String) = removeKeys { it.startsWith("$BANDS_PREFIX.$scope.") || it.startsWith("$LEARNING_PREFIX.$scope.") }

    private fun removeKeys(matches: (String) -> Boolean) {
        val editor = prefs.edit()
        prefs.all.keys.filter(matches).forEach { editor.remove(it) }
        editor.apply()
    }

    private fun bandsKey(
        scope: String,
        group: Group,
    ) = "$BANDS_PREFIX.$scope.${group.key}"

    private fun learningKey(
        scope: String,
        group: Group,
    ) = "$LEARNING_PREFIX.$scope.${group.key}"

    private val Group.key get() = name.lowercase()

    private companion object {
        const val BANDS_PREFIX = "mtkKeptOff"
        const val LEARNING_PREFIX = "mtkKeptOffLearning"
    }
}

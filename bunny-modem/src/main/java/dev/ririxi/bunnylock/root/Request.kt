package dev.ririxi.bunnylock.root

import android.telephony.SubscriptionManager
import dev.ririxi.bunnylock.core.toIntList
import dev.ririxi.bunnylock.modem.R
import org.json.JSONObject

typealias Commands = Map<String, (Request) -> Any?>

// Every request names the SIM it is for, counted from 1, and its subscription when the app knows it.
class Request(
    val json: JSONObject,
) {
    val sim: Int = json.optInt("sim", 1)

    val slot: Int get() = sim - 1

    val subId: Int by lazy { Aosp.resolveSubId(json.optInt("subId", SubscriptionManager.INVALID_SUBSCRIPTION_ID), slot) }

    val knownSubId: Int get() = subId.also { if (it < 0) fail(R.string.server_unknown_subscription) }

    val persist: Boolean get() = json.optBoolean("persist", true)

    fun ints(key: String): List<Int> = json.optJSONArray(key)?.toIntList() ?: emptyList()

    fun intsOrNull(key: String): List<Int>? = if (isPresent(key)) ints(key) else null

    fun intOrNull(key: String): Int? = if (isPresent(key)) json.getInt(key) else null

    private fun isPresent(key: String): Boolean = json.has(key) && !json.isNull(key)
}

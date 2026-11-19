package dev.ririxi.bunnylock.backend.samsung

import android.telephony.SubscriptionManager
import dev.ririxi.bunnylock.root.Commands
import dev.ririxi.bunnylock.root.Request

internal val samsungCommands: Commands =
    mapOf(
        "samsung.menu" to { _ -> Samsung.openMenu() },
        "samsung.nrMode" to { Samsung.nrMode(it.slot, it.subId, it.dataSubId) },
        "samsung.setNrMode" to { Samsung.setNrMode(it.slot, it.subId, it.dataSubId, it.json.getInt("mode")) },
    )

private val Request.dataSubId: Int get() = json.optInt("dataSubId", SubscriptionManager.INVALID_SUBSCRIPTION_ID)

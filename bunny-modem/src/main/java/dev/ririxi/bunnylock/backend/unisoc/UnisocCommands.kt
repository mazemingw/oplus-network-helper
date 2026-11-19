package dev.ririxi.bunnylock.backend.unisoc

import dev.ririxi.bunnylock.root.Commands

internal val unisocCommands: Commands =
    mapOf(
        "unisoc.state" to { Unisoc.state(it.sim, it.json.optBoolean("nr")) },
        "unisoc.setBands" to { Unisoc.setBands(it.sim, it.intsOrNull("lte"), it.intsOrNull("nr")) },
    )

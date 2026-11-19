package dev.ririxi.bunnylock.backend.pixel

import dev.ririxi.bunnylock.root.Commands

internal val pixelCommands: Commands =
    mapOf(
        "pixel.state" to { Pixel.state(it.sim) },
        "pixel.setLte" to { Pixel.setLte(it.ints("bands")) },
        "pixel.setNr" to { Pixel.setNr(it.ints("bands")) },
        "pixel.setNrMode" to { Pixel.setNrMode(it.sim, it.json.getString("value")) },
    )

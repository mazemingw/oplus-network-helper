package dev.ririxi.bunnylock.backend.mediatek

import dev.ririxi.bunnylock.root.Commands
import dev.ririxi.bunnylock.root.Request
import org.json.JSONObject

internal val mediatekCommands: Commands =
    mapOf(
        "mtk.findNode" to { JSONObject().put("node", Mediatek.findNode(it.sim).orEmpty()) },
        "mtk.state" to { Mediatek.state(it.node) },
        "mtk.setBands" to { Mediatek.setBands(it.node, it.intsOrNull("gsm"), it.intsOrNull("wcdma"), it.intsOrNull("lte"), it.intsOrNull("nr")) },
        "mtk.cellLock" to { Mediatek.cellLock(it.node, it.json.getInt("act"), it.json.getLong("arfcn"), it.intOrNull("pci")) },
        "mtk.cellUnlock" to { Mediatek.cellUnlock(it.node) },
        "mtk.setRat" to { Mediatek.setRat(it.node, it.json.getLong("mask")) },
        "mtk.setCa" to { Mediatek.setCarrierAggregation(it.node, it.json.getBoolean("enabled")) },
    )

private val Request.node: String get() = json.getString("node")

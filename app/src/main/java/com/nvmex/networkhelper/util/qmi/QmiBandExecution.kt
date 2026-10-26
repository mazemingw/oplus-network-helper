package com.nvmex.networkhelper.util.qmi

import org.json.JSONObject

/** Upstream d199690: a rejected NR-mode setter must not discard successful band changes. */
internal fun qmiExecuteBandCommands(commands: List<JSONObject>, nrModeSettable: Boolean,
                                    onModeRejected: () -> Unit = {},
                                    send: (JSONObject) -> Unit): Boolean {
    var modeRejected = false
    commands.forEach { command ->
        val mode = command.getString("cmd") == "mode_set"
        if (mode && !nrModeSettable) return@forEach
        try { send(command) }
        catch (e: QmiCommandException) {
            // Internal / invalid QMI command / not supported: explicit modem rejection.
            // Resource exhaustion, invalid client IDs and transport failures remain errors.
            if (!mode || e.command != "mode_set" || e.stage != "modem_rejected" || e.modemCode !in setOf(3, 71, 94)) throw e
            modeRejected = true
            onModeRejected()
        }
    }
    return modeRejected
}

data class QmiBandExecution(val device: QmiDeviceState, val nrModeRejected: Boolean)

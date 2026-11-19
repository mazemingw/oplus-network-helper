package dev.ririxi.bunnylock.backend.qualcomm

import dev.ririxi.bunnylock.root.Aosp
import dev.ririxi.bunnylock.root.Commands
import dev.ririxi.bunnylock.root.Request
import dev.ririxi.bunnylock.root.toTextJson
import dev.ririxi.bunnylock.root.unhex
import org.json.JSONObject

internal val qualcommCommands: Commands =
    mapOf(
        "qc.state" to { Qualcomm.state(it.sim) },
        "qc.serving" to { Qualcomm.serving(it.sim) },
        "qc.setBands" to { Qualcomm.setBands(it.sim, it.json.getString("kind"), it.ints("bands"), it.persist) },
        "qc.reset" to { Qualcomm.reset(it.sim, it.persist) },
        "qc.setRat" to { Qualcomm.setRat(it.sim, it.json.getLong("mask"), it.persist) },
        "qc.setNrMode" to { Qualcomm.setNrMode(it.sim, it.json.getInt("mode"), it.persist) },
        "qc.setPlmn" to { Qualcomm.setPlmn(it.sim, it.json.getInt("mcc"), it.json.getInt("mnc"), it.json.optBoolean("threeDigit"), it.persist) },
        "qc.clearPlmn" to { Qualcomm.clearPlmn(it.sim, it.persist) },
        "qc.lteLock" to { Qualcomm.lteLock(it.sim, it.json.getLong("earfcn"), it.ints("pcis")) },
        "qc.lteUnlock" to { Qualcomm.lteUnlock(it.sim) },
        "qc.nrLock" to { Qualcomm.nrLock(it.sim, it.json) },
        "qc.nrUnlock" to { Qualcomm.nrUnlock(it.sim) },
        "qc.reboot" to { _ -> Qualcomm.reboot() },
        "qc.reattach" to ::reattach,
        "qc.raw" to { Qualcomm.raw(it.sim, it.json.getInt("service"), it.json.getInt("msg"), it.json.optString("payload").unhex()) },
    )

private fun reattach(request: Request): JSONObject =
    try {
        Qualcomm.reattach(request.sim)
    } catch (e: Exception) {
        if (request.subId < 0) throw e
        Aosp.cycleSubscriberRadio(request.subId).put("qmiError", e.toTextJson())
    }

package dev.ririxi.bunnylock.root

import dev.ririxi.bunnylock.modem.ImsFeature
import org.json.JSONArray
import org.json.JSONObject

internal val androidCommands: Commands =
    mapOf(
        "aosp.state" to { Aosp.state(it.subId) },
        "aosp.carriers" to { PhysicalChannels.report(it.slot) },
        "aosp.setAllowed" to { Aosp.setAllowed(it.subId, it.json.getLong("mask"), it.slot) },
        "aosp.reapplyAllowed" to { Aosp.reapplyAllowed(it.subId, it.json.getLong("mask")) },
        "aosp.setNrDc" to { Aosp.setNrDc(it.subId, it.json.getBoolean("enabled")) },
        "aosp.ims" to { Aosp.ims(it.knownSubId) },
        "aosp.setIms" to { request ->
            val subId = request.knownSubId
            val feature = ImsFeature.entries.first { it.key == request.json.getString("feature") }
            Aosp.setIms(subId, feature, request.json.getBoolean("enabled"))
        },
        "aosp.setChannels" to {
            val specs = it.json.optJSONArray("specs") ?: JSONArray()
            Aosp.setChannels(it.subId, specs, it.json.optBoolean("split", true), it.json.optInt("userSpecCount", specs.length()))
        },
        "aosp.setPlmn" to { Aosp.setPlmn(it.subId, it.json.getString("plmn"), it.persist) },
        "aosp.setPlmnAuto" to { Aosp.setPlmnAutomatic(it.subId) },
        "aosp.rebootModem" to { Aosp.rebootModem(it.slot, it.subId) },
        "radio.cycle" to { _ -> Aosp.radioCycle() },
        "radio.cycleSim" to { Aosp.cycleSubscriberRadio(it.knownSubId) },
        "at" to {
            val reply = At.send(it.json.getString("node"), it.json.getString("command"), it.json.optInt("timeout", At.DEFAULT_TIMEOUT_MS))
            JSONObject().put("reply", reply)
        },
    )

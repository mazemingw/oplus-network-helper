package com.nvmex.networkhelper.model.network

import org.json.JSONObject

data class GlobalQosEvent(
    val timestamp: Long = System.currentTimeMillis(),

    val rlfCount: Int = 0,
    val rachWithUlGrantCount: Int = 0,
    val cellChangeCount: Int = 0,
    val isRedirectionOccur: Int = 0,

    val mobilitySysMode: Int = 0,
    val mobilityType: Int = 0,
    val mobilityStatus: Int = 0,
    val mobilitySourceRat: Int = 0,
    val mobilityTargetRat: Int = 0,

    val pagingSysMode: Int = 0,

    val nasSysMode: Int = 0,
    val emmState: Int = 0,
    val emmSubState: Int = 0,
    val mm5gState: Int = 0,
    val mm5gSubState: Int = 0,
    val plmnId: Int = 0
) {
    fun toJson(): String {
        return JSONObject().apply {
            put("timestamp", timestamp)

            put("rlfCount", rlfCount)
            put("rachWithUlGrantCount", rachWithUlGrantCount)
            put("cellChangeCount", cellChangeCount)
            put("isRedirectionOccur", isRedirectionOccur)

            put("mobilitySysMode", mobilitySysMode)
            put("mobilityType", mobilityType)
            put("mobilityStatus", mobilityStatus)
            put("mobilitySourceRat", mobilitySourceRat)
            put("mobilityTargetRat", mobilityTargetRat)

            put("pagingSysMode", pagingSysMode)

            put("nasSysMode", nasSysMode)
            put("emmState", emmState)
            put("emmSubState", emmSubState)
            put("mm5gState", mm5gState)
            put("mm5gSubState", mm5gSubState)
            put("plmnId", plmnId)
        }.toString()
    }

    companion object {
        fun fromJson(json: String): GlobalQosEvent {
            val o = JSONObject(json)
            return GlobalQosEvent(
                timestamp = o.optLong("timestamp", System.currentTimeMillis()),

                rlfCount = o.optInt("rlfCount", 0),
                rachWithUlGrantCount = o.optInt("rachWithUlGrantCount", 0),
                cellChangeCount = o.optInt("cellChangeCount", 0),
                isRedirectionOccur = o.optInt("isRedirectionOccur", 0),

                mobilitySysMode = o.optInt("mobilitySysMode", 0),
                mobilityType = o.optInt("mobilityType", 0),
                mobilityStatus = o.optInt("mobilityStatus", 0),
                mobilitySourceRat = o.optInt("mobilitySourceRat", 0),
                mobilityTargetRat = o.optInt("mobilityTargetRat", 0),

                pagingSysMode = o.optInt("pagingSysMode", 0),

                nasSysMode = o.optInt("nasSysMode", 0),
                emmState = o.optInt("emmState", 0),
                emmSubState = o.optInt("emmSubState", 0),
                mm5gState = o.optInt("mm5gState", 0),
                mm5gSubState = o.optInt("mm5gSubState", 0),
                plmnId = o.optInt("plmnId", 0)
            )
        }
    }
}
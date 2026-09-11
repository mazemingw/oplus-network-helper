package com.nvmex.networkhelper.model.network

import org.json.JSONObject

data class QosData(
    val timestamp: Long = System.currentTimeMillis(),

    // ===== 单卡归属主键 =====
    val subId: Int = -1,

    // ===== 基础无线状态 =====
    val rat: Int = -1,
    val endcState: Int = -1,
    val arfcn: Int = -1,
    val pci: Int = -1,
    val band: Int = -1,
    val dlBw: Int = -1,
    val rsrp: Int = -1,
    val rsrq: Int = -1,
    val snr: Int = -1,
    val svcStatus: Int = -1,

    // ===== UL =====
    val ulTimeStamp: Long = -1L,
    val ulPdcpNumDataPdu: Int = -1,
    val ulPdcpNumDropPdu: Int = -1,
    val ulPdcpTput: Long = 0L,
    val ulRlcNumDataPdu: Int = -1,
    val ulRlcRetx: Int = -1,
    val ulGrant: Int = -1,
    val ulBsr: Int = -1,
    val ulBler: Int = -1,

    // ===== DL =====
    val dlTimeStamp: Long = -1L,
    val dlPdcpNumDataPdu: Int = -1,
    val dlPdcpTput: Long = 0L,
    val dlRlcNumDataPdu: Int = -1,
    val dlRlcRetx: Int = -1,
    val dlRlcDrop: Int = -1,
    val dlMacPaddingBytes: Int = -1,
    val dlPdcpNumMissToUppPdu: Int = -1,
    val dlBler: Int = -1,

    // ===== 时延 / 小区 =====
    val latency: Int = -1,
    val cellId: Long = -1L,
    val nr5gScs: Int = -1,
    val cellLoad: Int = -1,

    // ===== 随机接入 =====
    val rachCount: Int = -1,
    val rachAbortCount: Int = -1,

    // ===== 双卡 / RRC =====
    val sub1RrcState: Int = -1,
    val sub2RrcState: Int = -1,
    val isDualSimConflict: Int = 0,

    // ===== 射频 / 功率 =====
    val calcPower: Int = -1,
    val mtpl: Int = -1,
    val pathLoss: Int = -1,
    val fbrxCount: Int = -1,

    // ===== 限速 / 链路报告 =====
    val linkReport: Boolean = false,
    val limitSpeedFlag: Int = 0,
    val limitSpeedRate: Int = 0
) {
    fun toJson(): String {
        return JSONObject().apply {
            put("timestamp", timestamp)
            put("subId", subId)

            put("rat", rat)
            put("endcState", endcState)
            put("arfcn", arfcn)
            put("pci", pci)
            put("band", band)
            put("dlBw", dlBw)
            put("rsrp", rsrp)
            put("rsrq", rsrq)
            put("snr", snr)
            put("svcStatus", svcStatus)

            put("ulTimeStamp", ulTimeStamp)
            put("ulPdcpNumDataPdu", ulPdcpNumDataPdu)
            put("ulPdcpNumDropPdu", ulPdcpNumDropPdu)
            put("ulPdcpTput", ulPdcpTput)
            put("ulRlcNumDataPdu", ulRlcNumDataPdu)
            put("ulRlcRetx", ulRlcRetx)
            put("ulGrant", ulGrant)
            put("ulBsr", ulBsr)
            put("ulBler", ulBler)

            put("dlTimeStamp", dlTimeStamp)
            put("dlPdcpNumDataPdu", dlPdcpNumDataPdu)
            put("dlPdcpTput", dlPdcpTput)
            put("dlRlcNumDataPdu", dlRlcNumDataPdu)
            put("dlRlcRetx", dlRlcRetx)
            put("dlRlcDrop", dlRlcDrop)
            put("dlMacPaddingBytes", dlMacPaddingBytes)
            put("dlPdcpNumMissToUppPdu", dlPdcpNumMissToUppPdu)
            put("dlBler", dlBler)

            put("latency", latency)
            put("cellId", cellId)
            put("nr5gScs", nr5gScs)
            put("cellLoad", cellLoad)

            put("rachCount", rachCount)
            put("rachAbortCount", rachAbortCount)

            put("sub1RrcState", sub1RrcState)
            put("sub2RrcState", sub2RrcState)
            put("isDualSimConflict", isDualSimConflict)

            put("calcPower", calcPower)
            put("mtpl", mtpl)
            put("pathLoss", pathLoss)
            put("fbrxCount", fbrxCount)

            put("linkReport", linkReport)
            put("limitSpeedFlag", limitSpeedFlag)
            put("limitSpeedRate", limitSpeedRate)
        }.toString()
    }

    companion object {
        fun fromJson(json: String): QosData {
            val o = JSONObject(json)
            return QosData(
                timestamp = o.optLong("timestamp", System.currentTimeMillis()),
                subId = o.optInt("subId", -1),

                rat = o.optInt("rat", -1),
                endcState = o.optInt("endcState", -1),
                arfcn = o.optInt("arfcn", -1),
                pci = o.optInt("pci", -1),
                band = o.optInt("band", -1),
                dlBw = o.optInt("dlBw", -1),
                rsrp = o.optInt("rsrp", -1),
                rsrq = o.optInt("rsrq", -1),
                snr = o.optInt("snr", -1),
                svcStatus = o.optInt("svcStatus", -1),

                ulTimeStamp = o.optLong("ulTimeStamp", -1L),
                ulPdcpNumDataPdu = o.optInt("ulPdcpNumDataPdu", -1),
                ulPdcpNumDropPdu = o.optInt("ulPdcpNumDropPdu", -1),
                ulPdcpTput = o.optLong("ulPdcpTput", 0L),
                ulRlcNumDataPdu = o.optInt("ulRlcNumDataPdu", -1),
                ulRlcRetx = o.optInt("ulRlcRetx", -1),
                ulGrant = o.optInt("ulGrant", -1),
                ulBsr = o.optInt("ulBsr", -1),
                ulBler = o.optInt("ulBler", -1),

                dlTimeStamp = o.optLong("dlTimeStamp", -1L),
                dlPdcpNumDataPdu = o.optInt("dlPdcpNumDataPdu", -1),
                dlPdcpTput = o.optLong("dlPdcpTput", 0L),
                dlRlcNumDataPdu = o.optInt("dlRlcNumDataPdu", -1),
                dlRlcRetx = o.optInt("dlRlcRetx", -1),
                dlRlcDrop = o.optInt("dlRlcDrop", -1),
                dlMacPaddingBytes = o.optInt("dlMacPaddingBytes", -1),
                dlPdcpNumMissToUppPdu = o.optInt("dlPdcpNumMissToUppPdu", -1),
                dlBler = o.optInt("dlBler", -1),

                latency = o.optInt("latency", -1),
                cellId = o.optLong("cellId", -1L),
                nr5gScs = o.optInt("nr5gScs", -1),
                cellLoad = o.optInt("cellLoad", -1),

                rachCount = o.optInt("rachCount", -1),
                rachAbortCount = o.optInt("rachAbortCount", -1),

                sub1RrcState = o.optInt("sub1RrcState", -1),
                sub2RrcState = o.optInt("sub2RrcState", -1),
                isDualSimConflict = o.optInt("isDualSimConflict", 0),

                calcPower = o.optInt("calcPower", -1),
                mtpl = o.optInt("mtpl", -1),
                pathLoss = o.optInt("pathLoss", -1),
                fbrxCount = o.optInt("fbrxCount", -1),

                linkReport = o.optBoolean("linkReport", false),
                limitSpeedFlag = o.optInt("limitSpeedFlag", 0),
                limitSpeedRate = o.optInt("limitSpeedRate", 0)
            )
        }
    }
}
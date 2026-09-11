package com.nvmex.networkhelper.xposed.translator

import de.robv.android.xposed.XposedHelpers

object NrcaTranslator {

    fun activityState(v: Int): String = when (v) {
        0 -> "Deconfigured"
        1 -> "Configured Deactivated"
        2 -> "Configured Activated"
        else -> "Invalid($v)"
    }

    fun bandwidth(v: Int): String = when (v) {
        0 -> "NR5G_BW_5MHZ"
        1 -> "NR5G_BW_10MHZ"
        2 -> "NR5G_BW_15MHZ"
        3 -> "NR5G_BW_20MHZ"
        4 -> "NR5G_BW_25MHZ"
        5 -> "NR5G_BW_30MHZ"
        6 -> "NR5G_BW_35MHZ"
        7 -> "NR5G_BW_40MHZ"
        8 -> "NR5G_BW_45MHZ"
        9 -> "NR5G_BW_50MHZ"
        10 -> "NR5G_BW_60MHZ"
        11 -> "NR5G_BW_70MHZ"
        12 -> "NR5G_BW_80MHZ"
        13 -> "NR5G_BW_90MHZ"
        14 -> "NR5G_BW_100MHZ"
        15 -> "NR5G_BW_200MHZ"
        16 -> "NR5G_BW_400MHZ"
        18 -> "NR5G_BW_INVALID"
        else -> "NR5G_BW_UNKNOWN($v)"
    }

    /**
     * ✅ 从 SubsysRadioIndication 实例尽力拿 slotId/phoneId
     * 你在日志里看到 instance 0/1，那一般就对应 slot 0/1。
     */
    fun tryGetSlotId(thisObj: Any?): Int {
        if (thisObj == null) return -1

        // 常见字段名候选
        val fieldNames = arrayOf(
            "mSlotId", "mPhoneId", "slotId", "phoneId",
            "mSlotIndex", "mPhoneIndex"
        )

        for (name in fieldNames) {
            val v = runCatching { XposedHelpers.getIntField(thisObj, name) }.getOrNull()
            if (v != null) return v
        }

        // 常见方法名候选（有些实现只暴露 getter）
        val methodNames = arrayOf(
            "getSlotId", "getPhoneId", "slotId", "phoneId"
        )

        for (mn in methodNames) {
            val v = runCatching { XposedHelpers.callMethod(thisObj, mn) as? Int }.getOrNull()
            if (v != null) return v
        }

        return -1
    }

    /**
     * 复刻工程模式里的 nrCaBandToDisplay（原样逻辑，别改）
     */
    fun bandFromPresenter(i: Int): String {
        if (i == 0) return "BAND1"
        if (i == 1) return "BAND2"
        if (i == 2) return "BAND3"
        if (i == 6) return "BAND7"
        if (i == 7) return "BAND8"
        if (i == 24) return "BAND25"
        if (i == 25) return "BAND26"
        if (i == 49) return "BAND50"
        if (i == 50) return "BAND51"
        if (i == 64) return "BAND65"
        if (i == 65) return "BAND66"
        if (i == 69) return "BAND70"
        if (i == 70) return "BAND71"

        return when (i) {
            4 -> "BAND5"
            17 -> "BAND18"
            19 -> "BAND20"
            33 -> "BAND34"
            47 -> "BAND48"
            74 -> "BAND75"
            75 -> "BAND76"
            76 -> "BAND77"
            77 -> "BAND78"
            78 -> "BAND79"
            79 -> "BAND80"
            80 -> "BAND81"
            81 -> "BAND82"
            82 -> "BAND83"
            83 -> "BAND84"
            84 -> "BAND85"
            85 -> "BAND86"
            256 -> "BAND257"
            257 -> "BAND258"
            258 -> "BAND259"
            259 -> "BAND260"
            260 -> "BAND261"
            11 -> "BAND12"
            12 -> "BAND13"
            13 -> "BAND14"
            27 -> "BAND28"
            28 -> "BAND29"
            29 -> "BAND30"
            37 -> "BAND38"
            38 -> "BAND39"
            39 -> "BAND40"
            40 -> "BAND41"
            else -> "BAND_INVALID($i)"
        }
    }

    /**
     * ✅ 更“像人话”的 smart：优先输出 Nxx；同时保留 (BANDxx) 作为证据链
     * 你现在的 raw=40 -> BAND41 -> N41，这就非常符合你观察到的国内 CA 组合。
     */
    fun bandSmart(raw: Int): String {
        val legacy = bandFromPresenter(raw) // "BAND41"
        val n = Regex("""BAND(\d+)""").find(legacy)?.groupValues?.getOrNull(1)
            ?: return "N?(raw=$raw)"
        return "N$n "
    }

    fun formatCarrier(
        idx: Int,
        ccId: Int,
        sccId: Int,
        pci: Int,
        band: Int,
        dlEarfcn: Int,
        dlState: Int,
        dlBw: Int,
        ulState: Int,
        ulBw: Int
    ): String = buildString {
        append("carrier[").append(idx).append("] ")
        append("ccId=").append(ccId).append(" sccId=").append(sccId)
        append(" pci=").append(pci)
        append(" band=").append(bandSmart(band))
        append(" dlEarfcn=").append(dlEarfcn)
        append(" dlState=").append(activityState(dlState))
        append(" dlBW=").append(bandwidth(dlBw))
        append(" ulState=").append(activityState(ulState))
        append(" ulBW=").append(bandwidth(ulBw))
    }

    /** UI 用：band -> "41"（取不到就 "-"） */
    fun bandShort(raw: Int): String {
        val legacy = bandFromPresenter(raw) // e.g. "BAND41" or "BAND_INVALID(x)"
        val n = Regex("""BAND(\d+)""").find(legacy)?.groupValues?.getOrNull(1)
        return n ?: "-"
    }

    /** UI 用：dlBwRaw -> "100M"（取不到就 "-"） */
    fun bandwidthShort(v: Int): String {
        // 你的 bandwidth(v) 形如 "NR5G_BW_100MHZ"
        val s = bandwidth(v)
        val mhz = Regex("""BW_(\d+)MHZ""").find(s)?.groupValues?.getOrNull(1)
        return mhz?.let { "${it}M" } ?: "-"
    }

    /** UI 用：拼 chip 文本，如 "41 · 100M" */
    private fun isBwValid(v: Int): Boolean {
        val s = bandwidth(v)
        return Regex("""BW_(\d+)MHZ""").containsMatchIn(s)
    }

    fun toChipText(bandRaw: Int, dlBwRaw: Int, ulBwRaw: Int): String {
        val b = bandShort(bandRaw)

        val dlOk = isBwValid(dlBwRaw)
        val ulOk = isBwValid(ulBwRaw)

        // ✅ UL-only 判定：DL 不可用，UL 可用
        val isUlOnly = !dlOk && ulOk

        val w = when {
            dlOk -> bandwidthShort(dlBwRaw)
            isUlOnly -> bandwidthShort(ulBwRaw)
            else -> "-" // 两边都不行
        }

        return when {
            b == "-" && w == "-" -> "-"
            w == "-" -> if (isUlOnly) "$b · UL" else b
            b == "-" -> w
            else -> "$b · $w"
        }
    }

    //解析 MHz 数值”的函数
    fun bandwidthMhzOrNull(v: Int): Int? {
        // bandwidth(v) 形如 "NR5G_BW_100MHZ"
        val s = bandwidth(v)
        return Regex("""BW_(\d+)MHZ""").find(s)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }





}

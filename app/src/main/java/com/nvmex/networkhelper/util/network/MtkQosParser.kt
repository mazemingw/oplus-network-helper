package com.nvmex.networkhelper.util.network

import com.nvmex.networkhelper.model.network.ModemQosInfo

/** TS 27.007 10.1.27 / 10.1.50. GBR/MBR are never substituted for AMBR. */
object MtkQosParser {
    fun parse(output: String, subId: Int, slot: Int, source: ModemQosInfo.Source): List<ModemQosInfo> {
        val lines = output.lineSequence().map { it.trim() }.toList()
        val apns = lines.filter { it.startsWith("+CGDCONT:") }.mapNotNull { line ->
            val fields = csv(line.substringAfter(':'))
            val cid = fields.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
            val apn = fields.getOrNull(2)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            cid to apn
        }.toMap()
        val prefix = if (source == ModemQosInfo.Source.NR_5GSM) "+C5GQOSRDP:" else "+CGEQOSRDP:"
        return lines.filter { it.startsWith(prefix) }.mapNotNull { line ->
            val fields = csv(line.substringAfter(':'))
            val cid = fields.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
            val apn = apns[cid] ?: return@mapNotNull null
            val qos = fields.getOrNull(1)?.toIntOrNull()?.takeIf { it in 1..254 } ?: return@mapNotNull null
            val lower = apn.lowercase(java.util.Locale.ROOT)
            val ims = lower == "ims" || lower.startsWith("ims.")
            if (!ims && (lower.split('.').first() in setOf("sos", "emergency", "supl", "xcap", "mms", "fota") || qos in 1..4)) return@mapNotNull null
            fun rate(index: Int) = fields.getOrNull(index)?.toLongOrNull()?.takeIf { it >= 0 }
            ModemQosInfo(subId, slot, if (ims) ModemQosInfo.SessionKind.IMS else ModemQosInfo.SessionKind.DATA,
                apn, null, qos, if (source == ModemQosInfo.Source.NR_5GSM) qos else null, null,
                rate(6), rate(7), source)
        }
    }

    private fun csv(value: String): List<String> {
        val result = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        for (char in value) when {
            char == '"' -> quoted = !quoted
            char == ',' && !quoted -> { result += field.toString().trim(); field.clear() }
            else -> field.append(char)
        }
        result += field.toString().trim()
        return result
    }
}

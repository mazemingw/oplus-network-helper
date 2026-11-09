package com.nvmex.networkhelper.util.diagnostics

/** Apply before persistence, upload and sharing. IMEI is sent only in its dedicated upload field. */
object DiagnosticRedactor {
    private val sensitiveKeys = Regex("(?i).*(?:imei|imsi|iccid|meid|serial|secret|token|password|passwd|authorization|cookie|apikey|accesskey|privatekey|androidid|subscriberid|phonenumber|msisdn|latitude|longitude|sessionid).*")
    fun isSensitiveKey(key: String): Boolean {
        val normalized = key.replace(Regex("[^A-Za-z0-9]"), "")
        return sensitiveKeys.matches(normalized) || normalized.lowercase() in setOf("pwd", "phone", "lat", "lon", "lng", "ssid", "bssid", "mac", "macaddress", "ip", "ipaddress", "nci", "eci", "cellid")
    }
    private val assignments = Regex(
        """(?i)(["']?(?:imei\d*|imsi|iccid|meid|android[_ -]?id|serial(?:no|number)?|hardware_serial|phone(?:number)?|msisdn|password|passwd|pwd|secret|vault_secret|token|api[_-]?key|access[_-]?key(?:[_-]?(?:id|secret))?|authorization|cookie|ssid|bssid|latitude|longitude|lat|lon|lng|nci|eci|cell[_-]?id)["']?\s*[:=]\s*)(?:"[^"\r\n]*"|'[^'\r\n]*'|[^\s,;，；}\]]+)"""
    )
    private val patterns = listOf(
        Regex("(?i)Bearer\\s+[A-Za-z0-9._~+/=-]+"),
        Regex("(?i)https?://[^\\s<>\"']+"),
        Regex("(?<![0-9])(?:[0-9][ -]?){14,21}(?![0-9])"),
        Regex("(?i)(?<![a-f0-9])(?:[a-f0-9]{2}[:-]){5}[a-f0-9]{2}(?![a-f0-9])"),
        Regex("(?<![0-9])(?:[0-9]{1,3}\\.){3}[0-9]{1,3}(?![0-9])"),
        Regex("(?i)(?<![a-f0-9:])(?:(?:[a-f0-9]{1,4}:){7}[a-f0-9]{1,4}|[a-f0-9:]*::[a-f0-9:]+)(?![a-f0-9:])"),
        Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"),
        Regex("(?<![0-9])(?:\\+?86[- ]?)?1[3-9][0-9]{9}(?![0-9])"),
        Regex("(?i)(?<![a-f0-9])[a-f0-9]{32,}(?![a-f0-9])"),
        Regex("(?i)(?:\\b[0-9a-f]{2}[ ,:]){8,}[0-9a-f]{2}\\b"),
        Regex("(?<![0-9])[-+]?[0-9]{1,3}\\.[0-9]{4,}(?![0-9])"),
        Regex("(?i)(?:/storage/emulated/[0-9]+|/sdcard)/(?:Download/)?(?!EFS-BACKUP(?:/|\\b))[^\\s\"']+"),
        Regex("(?i)(?:hex|payload|raw[_ -]?data|data[_ -]?hex)\\s*[:=].*"),
    )

    fun redact(value: String): String {
        val builds = mutableListOf<String>()
        val protected = Regex("(\\bat [\\w.$<>]+\\()r8-map-id-([a-f0-9]{64})(:\\d+\\))").replace(value) { frame ->
            builds.add(frame.value)
            "__DIAGNOSTIC_BUILD_FRAME_${builds.lastIndex}__"
        }
        var result = assignments.replace(protected) { it.groupValues[1] + "\"[已隐藏]\"" }
        patterns.forEach { result = it.replace(result, "[已隐藏]") }
        builds.forEachIndexed { index, frame -> result = result.replace("__DIAGNOSTIC_BUILD_FRAME_${index}__", frame) }
        return result
    }
}

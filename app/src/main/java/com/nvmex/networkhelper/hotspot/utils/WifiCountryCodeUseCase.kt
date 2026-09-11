package com.nvmex.networkhelper.hotspot.utils

import com.nvmex.networkhelper.util.shell.ShellResult
import com.nvmex.networkhelper.util.shell.SuShellRunner

class WifiCountryCodeUseCase(
    private val su: SuShellRunner
) {
    fun readCountryCode(timeoutMs: Long = 800): String? {
        // 1) cmd wifi get-country-code
        su.execSu("cmd wifi get-country-code", timeoutMs).also { r ->
            parseCountry(r)?.let { return it }
        }
        // 2) cmd wifi get-country
        su.execSu("cmd wifi get-country", timeoutMs).also { r ->
            parseCountry(r)?.let { return it }
        }
        // 3) cmd wifi force-country-code (弱策略：有的会打印当前信息)
        su.execSu("cmd wifi force-country-code", timeoutMs).also { r ->
            parseCountry(r)?.let { return it }
        }
        return null
    }

    fun forceCountryCode(code: String, timeoutMs: Long = 1500): ShellResult {
        // 注意：这里按你要求固定 enabled
        return su.execSu("cmd wifi force-country-code enabled ${code.uppercase()}", timeoutMs)
    }

    private fun parseCountry(r: ShellResult): String? {
        if (r.code != 0) return null
        val text = (r.out + "\n" + r.err).trim()
        // 常见返回：US / countryCode: US / Country code: US
        val regexes = listOf(
            Regex("""\b([A-Z]{2})\b"""),
            Regex("""country\s*code\s*[:=]\s*([A-Z]{2})""", RegexOption.IGNORE_CASE),
            Regex("""countryCode\s*[:=]\s*([A-Z]{2})""", RegexOption.IGNORE_CASE)
        )
        for (re in regexes) {
            val m = re.find(text) ?: continue
            val g = m.groupValues.getOrNull(1) ?: continue
            if (g.length == 2 && g.all { it.isLetter() }) return g.uppercase()
        }
        return null
    }
}

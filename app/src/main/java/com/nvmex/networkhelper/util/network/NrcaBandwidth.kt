package com.nvmex.networkhelper.util.network

import java.util.concurrent.TimeUnit

/** The 3 MHz capable modem inserts a new entry at index zero of the NRCA bandwidth enum. */
enum class NrcaBandwidthEncoding(val wireValue: String, val offset: Int) {
    LEGACY("legacy", 0),
    WITH_3_MHZ("with_3_mhz", 1);

    fun mhzOrNull(raw: Int): Int? {
        if (this == WITH_3_MHZ && raw == 0) return 3
        // Check the raw range before subtracting, including for Int.MIN_VALUE.
        if (raw < offset || raw > 16 + offset) return null
        return LEGACY_MHZ[raw - offset]
    }

    val invalidRaw: Int get() = 18 + offset

    companion object {
        private val LEGACY_MHZ = intArrayOf(
            5, 10, 15, 20, 25, 30, 35, 40, 45, 50, 60, 70, 80, 90, 100, 200, 400
        )

        fun fromWireValue(value: String?): NrcaBandwidthEncoding? =
            entries.firstOrNull { it.wireValue == value }

        /** Matches PYB110's EngineerSystemProperties and OplusServiceStateTrackerUtil. */
        internal fun forProperties(cpuInfo: String?, support3Mhz: String?): NrcaBandwidthEncoding =
            if (cpuInfo == "SM8975" || support3Mhz?.toIntOrNull() == 1) WITH_3_MHZ else LEGACY
    }
}

object NrcaBandwidth {
    val deviceEncoding: NrcaBandwidthEncoding by lazy {
        NrcaBandwidthEncoding.forProperties(
            readProperty("ro.product.oplus.cpuinfo"),
            readProperty("ro.oplus.radio.support.nr5g.bw.3mhz")
        )
    }

    private fun readProperty(key: String): String {
        val reflected = runCatching {
            Class.forName("android.os.SystemProperties")
                .getMethod("get", String::class.java, String::class.java)
                .invoke(null, key, "") as String
        }.getOrNull()
        if (reflected != null) return reflected

        // Normal app processes can restrict hidden APIs; getprop needs neither root nor LSPosed.
        return runCatching {
            val process = ProcessBuilder("/system/bin/getprop", key).start()
            try {
                if (process.waitFor(300, TimeUnit.MILLISECONDS) && process.exitValue() == 0) {
                    process.inputStream.bufferedReader().use { it.readText().trim() }
                } else {
                    ""
                }
            } finally {
                process.destroy()
                process.inputStream.close()
                process.errorStream.close()
                process.outputStream.close()
            }
        }.getOrDefault("")
    }
}

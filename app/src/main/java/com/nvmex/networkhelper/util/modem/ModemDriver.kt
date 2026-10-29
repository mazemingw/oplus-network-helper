package com.nvmex.networkhelper.util.modem

import dev.ririxi.bunnylock.modem.Backend

enum class ModemDriver(val title: String, val backend: Backend? = null) {
    ORIGINAL_QMI("高通 QMI · 原有驱动"),
    AUTO("自动识别 · bunnylock"),
    BUNNY_QMI("高通 QMI · bunnylock", Backend.QUALCOMM),
    MEDIATEK("联发科 AT · bunnylock", Backend.MEDIATEK),
    PIXEL("Pixel · bunnylock", Backend.PIXEL),
    ANDROID("Android HAL · bunnylock", Backend.ANDROID);

    val isBunny get() = this != ORIGINAL_QMI
}

/** No implicit substitution: manual selections must either work or report why unavailable. */
fun driverBackend(driver: ModemDriver, detected: Backend?): Backend? =
    if (driver == ModemDriver.AUTO) detected else driver.backend

/** AT node and PCI validation are shared by the host's editor and its tests. */
fun parseModemPcis(text: String, nr: Boolean): List<Int> {
    if (text.isBlank()) return emptyList()
    return text.trim().split(Regex("[,，;；\\s]+")).filter(String::isNotBlank).map {
        requireNotNull(it.toIntOrNull()) { "PCI 必须是整数" }
    }.distinct().also { values ->
        require(values.all { it in 0..(if (nr) 1007 else 503) }) { "PCI 范围：0–${if (nr) 1007 else 503}" }
    }
}

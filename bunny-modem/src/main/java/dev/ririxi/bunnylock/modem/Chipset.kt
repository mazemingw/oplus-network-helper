package dev.ririxi.bunnylock.modem

import androidx.annotation.StringRes

enum class Chipset(
    @StringRes val label: Int,
) {
    QUALCOMM(R.string.vendor_qualcomm),
    MEDIATEK(R.string.vendor_mediatek),
    TENSOR(R.string.vendor_tensor),
    EXYNOS(R.string.vendor_exynos),
    UNISOC(R.string.vendor_unisoc),
    UNKNOWN(R.string.vendor_unknown),
    ;

    companion object {
        fun named(name: String): Chipset = entries.firstOrNull { it.name == name } ?: UNKNOWN
    }
}

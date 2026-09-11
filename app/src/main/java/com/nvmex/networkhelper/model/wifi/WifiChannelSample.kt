package com.nvmex.networkhelper.model.wifi

data class WifiChannelSample(
    val ssid: String,
    val bssid: String? = null,
    val frequencyMhz: Int,
    val channel: Int,
    val levelDbm: Int,
    val channelWidthMhz: Int? = null,
    val wifiGeneration: String = "-",
    val ant: String = "-",
    val txPwrDbm: Int? = null,
    val ueCount: Int? = null,
    val busyPercent: Int? = null,
    val beamforming: String = "-",
    val roaming: String = "-"
)

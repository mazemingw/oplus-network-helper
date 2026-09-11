package com.nvmex.networkhelper.hotspot

data class HotspotUiState(
    val phase: Phase = Phase.IDLE,

    // ===== Tether broadcast =====
    val tetherIfaces: List<String> = emptyList(),

    // ===== Config snapshot (what we configured / what system reports) =====
    val ssid: String? = null,
    val passphrase: String? = null,
    val configBand: Int? = null,
    val configChannel: Int? = null,

    // ===== Soft AP runtime info (what actually started) =====
    val apState: String? = null,
    val apFailureReason: String? = null,
    val apFrequencyMhz: Int? = null,
    val apChannel: Int? = null,
    val apBandText: String? = null,
    val apBandwidth: String? = null,
    val apWifiStandard: Int? = null,
    val apClients: Int? = null,
    val configMaxBandwidth: Int? = null,
    // ===== Capability =====
    val capMaxClients: Int? = null,
    val capBand24: Boolean? = null,
    val capBand5: Boolean? = null,
    val capBand6: Boolean? = null,
    val capBand60: Boolean? = null,
    val capCountryCode: String? = null,
    val capChannels2g: List<ChannelOption> = emptyList(),
    val capChannels5g: List<ChannelOption> = emptyList(),
    val capChannels6g: List<ChannelOption> = emptyList(),
    val capChannels60g: List<ChannelOption> = emptyList(),


    // ===== Config apply UX =====
    val configApplying: Boolean = false,
    val configApplyResult: String? = null,

    val lastError: String? = null,
    val lastEvent: String? = null,
)
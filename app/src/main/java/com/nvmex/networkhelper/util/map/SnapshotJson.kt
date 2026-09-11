package com.nvmex.networkhelper.util.map

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName
import com.nvmex.networkhelper.model.network.NetworkPanelUiState

object SnapshotJson {
    private val gson = Gson()

    // 持久化用稳定 schema，避免 R8 混淆字段名导致跨版本不可恢复。
    fun toJson(s: NetworkPanelUiState): String = gson.toJson(PersistedSnapshot.fromState(s))

    fun fromJson(json: String): NetworkPanelUiState {
        val obj = runCatching { JsonParser.parseString(json).asJsonObject }.getOrNull()
        if (obj != null && (
                    obj.has("cellType") ||
                            obj.has("dataNetworkType") ||
                            obj.has("tac") ||
                            obj.has("pci") ||
                            obj.has("ci") ||
                            obj.has("arfcn")
                    )
        ) {
            return runCatching { gson.fromJson(json, PersistedSnapshot::class.java).toState() }
                .getOrElse { NetworkPanelUiState() }
        }

        // 旧版本兼容：回退到直接反序列化旧 NetworkPanelUiState 结构
        return runCatching { gson.fromJson(json, NetworkPanelUiState::class.java) }
            .getOrElse { NetworkPanelUiState() }
    }

    private data class PersistedSnapshot(
        @SerializedName("operatorName") val operatorName: String = "-",
        @SerializedName("mcc") val mcc: String = "-",
        @SerializedName("mnc") val mnc: String = "-",
        @SerializedName("dataNetworkType") val dataNetworkType: String = "-",
        @SerializedName("nrMode") val nrMode: String = "-",
        @SerializedName("cellType") val cellType: String = "-",
        @SerializedName("duplex") val duplex: String = "-",
        @SerializedName("tac") val tac: String = "-",
        @SerializedName("pci") val pci: String = "-",
        @SerializedName("ci") val ci: String = "-",
        @SerializedName("arfcn") val arfcn: String = "-",
        @SerializedName("band") val band: String = "-",
        @SerializedName("freqDl") val freqDl: String = "-",
        @SerializedName("freqUl") val freqUl: String = "-",
        @SerializedName("rssi") val rssi: String = "-",
        @SerializedName("rsrp") val rsrp: String = "-",
        @SerializedName("rsrq") val rsrq: String = "-",
        @SerializedName("sinr") val sinr: String = "-",
        @SerializedName("ssRsrp") val ssRsrp: String = "-",
        @SerializedName("ssRsrq") val ssRsrq: String = "-",
        @SerializedName("ssSinr") val ssSinr: String = "-",
        @SerializedName("updatedAt") val updatedAt: Long = 0L,
        @SerializedName("subId") val subId: Int = -1
    ) {
        fun toState(): NetworkPanelUiState {
            return NetworkPanelUiState(
                operatorName = operatorName,
                mcc = mcc,
                mnc = mnc,
                dataNetworkType = dataNetworkType,
                nrMode = nrMode,
                cellType = cellType,
                duplex = duplex,
                tac = tac,
                pci = pci,
                ci = ci,
                arfcn = arfcn,
                band = band,
                freqDl = freqDl,
                freqUl = freqUl,
                rssi = rssi,
                rsrp = rsrp,
                rsrq = rsrq,
                sinr = sinr,
                ssRsrp = ssRsrp,
                ssRsrq = ssRsrq,
                ssSinr = ssSinr,
                updatedAt = updatedAt,
                subId = subId
            )
        }

        companion object {
            fun fromState(s: NetworkPanelUiState): PersistedSnapshot {
                return PersistedSnapshot(
                    operatorName = s.operatorName,
                    mcc = s.mcc,
                    mnc = s.mnc,
                    dataNetworkType = s.dataNetworkType,
                    nrMode = s.nrMode,
                    cellType = s.cellType,
                    duplex = s.duplex,
                    tac = s.tac,
                    pci = s.pci,
                    ci = s.ci,
                    arfcn = s.arfcn,
                    band = s.band,
                    freqDl = s.freqDl,
                    freqUl = s.freqUl,
                    rssi = s.rssi,
                    rsrp = s.rsrp,
                    rsrq = s.rsrq,
                    sinr = s.sinr,
                    ssRsrp = s.ssRsrp,
                    ssRsrq = s.ssRsrq,
                    ssSinr = s.ssSinr,
                    updatedAt = s.updatedAt,
                    subId = s.subId
                )
            }
        }
    }
}

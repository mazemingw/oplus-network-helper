package com.nvmex.networkhelper.viewmodel.signal

import android.content.Context
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nvmex.networkhelper.R
import com.nvmex.networkhelper.model.network.GlobalQosEvent
import com.nvmex.networkhelper.model.network.QosData
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import jakarta.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 用于 UI 显示的格式化 QoS 数据
 * 已移除那些不再适合作为单卡字段的“全局事件计数”。
 */
data class QosDisplayData(
    val subId: String,
    val rat: String,
    val accessMode: String,
    val endcState: String,
    val arfcn: String,
    val pci: String,
    val band: String,
    val dlBw: String,
    val rsrp: String,
    val rsrq: String,
    val snr: String,
    val svcStatus: String,

    val ulTimeStamp: String,
    val ulPdcpNumDataPdu: String,
    val ulPdcpNumDropPdu: String,
    val ulPdcpTput: String,
    val ulRlcNumDataPdu: String,
    val ulRlcRetx: String,
    val ulGrant: String,
    val ulBsr: String,
    val ulBler: String,

    val dlTimeStamp: String,
    val dlPdcpNumDataPdu: String,
    val dlPdcpTput: String,
    val dlRlcNumDataPdu: String,
    val dlRlcRetx: String,
    val dlRlcDrop: String,
    val dlMacPaddingBytes: String,
    val dlPdcpNumMissToUppPdu: String,
    val dlBler: String,

    val isDualSimConflict: String,
    val latency: String,
    val cellId: String,
    val nr5gScs: String,
    val sub1RrcState: String,
    val sub2RrcState: String,
    val rachCount: String,
    val rachAbortCount: String,
    val calcPower: String,
    val mtpl: String,
    val pathLoss: String,
    val fbrxCount: String,
    val cellLoad: String,

    val linkReport: String,
    val limitSpeedFlag: String,
    val limitSpeedRate: String
)

@HiltViewModel
class QosViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    companion object {
        private const val PLACEHOLDER = "-"
    }

    private val receiver = QosDataReceiver()

    private val _currentQosBySubId = MutableStateFlow<Map<Int, QosData>>(emptyMap())
    val currentQosBySubId: StateFlow<Map<Int, QosData>> = _currentQosBySubId.asStateFlow()

    private val _displayQosBySubId = MutableStateFlow<Map<Int, QosDisplayData>>(emptyMap())
    val displayQosBySubId: StateFlow<Map<Int, QosDisplayData>> = _displayQosBySubId.asStateFlow()

    private val _qosHistoryBySubId = MutableStateFlow<Map<Int, List<QosData>>>(emptyMap())
    val qosHistoryBySubId: StateFlow<Map<Int, List<QosData>>> = _qosHistoryBySubId.asStateFlow()

    private val historyMap = mutableMapOf<Int, MutableList<QosData>>()
    private val maxHistorySize = 100

    private val _globalEvent = MutableStateFlow<GlobalQosEvent?>(null)
    val globalEvent: StateFlow<GlobalQosEvent?> = _globalEvent.asStateFlow()

    init {
        receiver.register(appContext, this)
    }

    override fun onCleared() {
        receiver.unregister(appContext)
        super.onCleared()
    }

    fun updateQosData(subId: Int, data: QosData) {
        viewModelScope.launch {
            _currentQosBySubId.update { old ->
                old.toMutableMap().apply {
                    this[subId] = data
                }
            }

            _displayQosBySubId.update { old ->
                val freshDisplay = data.toDisplayData()
                old.toMutableMap().apply {
                    this[subId] = mergeDisplay(old[subId], freshDisplay)
                }
            }

            val list = historyMap.getOrPut(subId) { mutableListOf() }
            list.add(data)
            if (list.size > maxHistorySize) {
                list.removeAt(0)
            }

            _qosHistoryBySubId.update {
                historyMap.mapValues { entry -> entry.value.toList() }
            }
        }
    }

    fun updateGlobalEvent(event: GlobalQosEvent) {
        viewModelScope.launch {
            _globalEvent.value = event
        }
    }

    fun clearHistory(subId: Int? = null) {
        viewModelScope.launch {
            if (subId == null) {
                historyMap.clear()
                _qosHistoryBySubId.value = emptyMap()
            } else {
                historyMap.remove(subId)
                _qosHistoryBySubId.value = historyMap.mapValues { it.value.toList() }
            }
        }
    }

    fun getCurrentQos(subId: Int): QosData? = _currentQosBySubId.value[subId]

    fun getDisplayQos(subId: Int): QosDisplayData? = _displayQosBySubId.value[subId]

    fun getHistory(subId: Int): List<QosData> = _qosHistoryBySubId.value[subId].orEmpty()

    fun getAverageDownloadSpeed(subId: Int): Double {
        val list = getHistory(subId)
        return if (list.isEmpty()) 0.0 else list.map { it.dlPdcpTput * 8 / 1_000_000.0 }.average()
    }

    fun getAverageLatency(subId: Int): Double {
        val list = getHistory(subId)
        return if (list.isEmpty()) 0.0 else list.map { it.latency.toDouble() }.average()
    }

    fun getMaxDownloadSpeed(subId: Int): Long {
        return getHistory(subId).maxOfOrNull { it.dlPdcpTput } ?: 0L
    }

    fun getMinLatency(subId: Int): Int {
        return getHistory(subId).minOfOrNull { it.latency } ?: 0
    }

    private fun isChineseLocale(): Boolean {
        val locale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            appContext.resources.configuration.locales[0]
        } else {
            @Suppress("DEPRECATION")
            appContext.resources.configuration.locale
        }
        return locale.language.equals("zh", ignoreCase = true)
    }

    private fun zhEn(zh: String, en: String): String {
        return if (isChineseLocale()) zh else en
    }

    private fun unknownValue(code: Int): String {
        return if (isChineseLocale()) {
            "未知 ($code)"
        } else {
            "Unknown ($code)"
        }
    }

    private fun mergeValue(newValue: String, oldValue: String): String {
        return if (newValue == PLACEHOLDER && oldValue.isNotBlank()) oldValue else newValue
    }

    private fun mergeDisplay(old: QosDisplayData?, fresh: QosDisplayData): QosDisplayData {
        if (old == null) return fresh
        return fresh.copy(
            subId = mergeValue(fresh.subId, old.subId),
            rat = mergeValue(fresh.rat, old.rat),
            accessMode = mergeValue(fresh.accessMode, old.accessMode),
            endcState = mergeValue(fresh.endcState, old.endcState),
            arfcn = mergeValue(fresh.arfcn, old.arfcn),
            pci = mergeValue(fresh.pci, old.pci),
            band = mergeValue(fresh.band, old.band),
            dlBw = mergeValue(fresh.dlBw, old.dlBw),
            rsrp = mergeValue(fresh.rsrp, old.rsrp),
            rsrq = mergeValue(fresh.rsrq, old.rsrq),
            snr = mergeValue(fresh.snr, old.snr),
            svcStatus = mergeValue(fresh.svcStatus, old.svcStatus),
            ulTimeStamp = mergeValue(fresh.ulTimeStamp, old.ulTimeStamp),
            ulPdcpNumDataPdu = mergeValue(fresh.ulPdcpNumDataPdu, old.ulPdcpNumDataPdu),
            ulPdcpNumDropPdu = mergeValue(fresh.ulPdcpNumDropPdu, old.ulPdcpNumDropPdu),
            ulPdcpTput = mergeValue(fresh.ulPdcpTput, old.ulPdcpTput),
            ulRlcNumDataPdu = mergeValue(fresh.ulRlcNumDataPdu, old.ulRlcNumDataPdu),
            ulRlcRetx = mergeValue(fresh.ulRlcRetx, old.ulRlcRetx),
            ulGrant = mergeValue(fresh.ulGrant, old.ulGrant),
            ulBsr = mergeValue(fresh.ulBsr, old.ulBsr),
            ulBler = mergeValue(fresh.ulBler, old.ulBler),
            dlTimeStamp = mergeValue(fresh.dlTimeStamp, old.dlTimeStamp),
            dlPdcpNumDataPdu = mergeValue(fresh.dlPdcpNumDataPdu, old.dlPdcpNumDataPdu),
            dlPdcpTput = mergeValue(fresh.dlPdcpTput, old.dlPdcpTput),
            dlRlcNumDataPdu = mergeValue(fresh.dlRlcNumDataPdu, old.dlRlcNumDataPdu),
            dlRlcRetx = mergeValue(fresh.dlRlcRetx, old.dlRlcRetx),
            dlRlcDrop = mergeValue(fresh.dlRlcDrop, old.dlRlcDrop),
            dlMacPaddingBytes = mergeValue(fresh.dlMacPaddingBytes, old.dlMacPaddingBytes),
            dlPdcpNumMissToUppPdu = mergeValue(fresh.dlPdcpNumMissToUppPdu, old.dlPdcpNumMissToUppPdu),
            dlBler = mergeValue(fresh.dlBler, old.dlBler),
            isDualSimConflict = mergeValue(fresh.isDualSimConflict, old.isDualSimConflict),
            latency = mergeValue(fresh.latency, old.latency),
            cellId = mergeValue(fresh.cellId, old.cellId),
            nr5gScs = mergeValue(fresh.nr5gScs, old.nr5gScs),
            sub1RrcState = mergeValue(fresh.sub1RrcState, old.sub1RrcState),
            sub2RrcState = mergeValue(fresh.sub2RrcState, old.sub2RrcState),
            rachCount = mergeValue(fresh.rachCount, old.rachCount),
            rachAbortCount = mergeValue(fresh.rachAbortCount, old.rachAbortCount),
            calcPower = mergeValue(fresh.calcPower, old.calcPower),
            mtpl = mergeValue(fresh.mtpl, old.mtpl),
            pathLoss = mergeValue(fresh.pathLoss, old.pathLoss),
            fbrxCount = mergeValue(fresh.fbrxCount, old.fbrxCount),
            cellLoad = mergeValue(fresh.cellLoad, old.cellLoad),
            linkReport = mergeValue(fresh.linkReport, old.linkReport),
            limitSpeedFlag = mergeValue(fresh.limitSpeedFlag, old.limitSpeedFlag),
            limitSpeedRate = mergeValue(fresh.limitSpeedRate, old.limitSpeedRate)
        )
    }

    fun QosData.toDisplayData(): QosDisplayData {
        fun formatValue(
            value: Any?,
            unit: String = "",
            invalidValues: Set<Any> = setOf(-1, 32767, -1L, 32767L)
        ): String {
            return when {
                value == null -> PLACEHOLDER
                value in invalidValues -> PLACEHOLDER
                else -> "$value${if (unit.isNotBlank()) " $unit" else ""}"
            }
        }

        fun formatDoubleValue(
            value: Int,
            divisor: Int = 10,
            unit: String,
            invalidValues: Set<Int> = setOf(-1, 32767)
        ): String {
            return if (value in invalidValues) PLACEHOLDER
            else "%.1f $unit".format(value / divisor.toDouble())
        }

        fun formatThroughput(bytesPerSec: Long): String {
            return if (bytesPerSec == -1L || bytesPerSec == 32767L) PLACEHOLDER
            else "%.2f Mbps".format(bytesPerSec * 8 / 1_000_000.0)
        }

        fun mapRat(rat: Int): String = when (rat) {
            0 -> "NONE"
            1 -> "GSM"
            2 -> "UMTS"
            3 -> "C2K"
            4 -> "LTE"
            5 -> "NR"
            254 -> "OTHER"
            255 -> "INVALID"
            else -> rat.toString()
        }

        fun mapEndcState(state: Int, rat: Int): String {
            if (rat == 5) return "N/A (NR/SA)"
            if (rat != 4) return "N/A"
            return when (state) {
                0 -> zhEn("不可用 (0)", "Unavailable (0)")
                1 -> zhEn("可用未连接 (1)", "Available, not connected (1)")
                2 -> zhEn("NSA已连接 (2)", "NSA connected (2)")
                else -> unknownValue(state)
            }
        }

        fun mapAccessMode(rat: Int, endcState: Int): String = when {
            rat == 5 -> "SA"
            rat == 4 && endcState == 2 -> "NSA"
            rat == 4 -> zhEn("非5G", "Non-5G")
            else -> zhEn("未知", "Unknown")
        }

        fun mapSvcStatus(status: Int): String = when (status) {
            0 -> zhEn("无", "None")
            1 -> zhEn("无服务", "No service")
            2 -> zhEn("受限服务", "Limited service")
            3 -> zhEn("服务中", "In service")
            else -> status.toString()
        }

        fun mapRrcState(state: Int): String = when (state) {
            0 -> zhEn("无", "None")
            1 -> zhEn("空闲态", "Idle")
            2 -> zhEn("去激活态", "Inactive")
            3 -> zhEn("连接态", "Connected")
            else -> state.toString()
        }

        fun mapNr5gScs(scs: Int): String = when (scs) {
            0 -> zhEn("无", "None")
            1 -> "15 kHz"
            2 -> "30 kHz"
            3 -> "60 kHz"
            4 -> "120 kHz"
            5 -> "240 kHz"
            else -> "$scs"
        }

        fun mapDlBw(bw: Int, rat: Int): String {
            if (bw == -1 || bw == 32767) return PLACEHOLDER
            return when (rat) {
                5 -> when (bw) {
                    0 -> zhEn("无", "None")
                    1 -> "5 MHz"
                    2 -> "10 MHz"
                    3 -> "15 MHz"
                    4 -> "20 MHz"
                    5 -> "25 MHz"
                    6 -> "30 MHz"
                    7 -> "40 MHz"
                    8 -> "50 MHz"
                    9 -> "60 MHz"
                    10 -> "70 MHz"
                    11 -> "80 MHz"
                    12 -> "90 MHz"
                    13 -> "100 MHz"
                    14 -> "200 MHz"
                    255 -> zhEn("无效", "Invalid")
                    else -> "$bw"
                }

                4 -> when (bw) {
                    0 -> zhEn("无", "None")
                    1 -> "1.4 MHz"
                    2 -> "3 MHz"
                    3 -> "5 MHz"
                    4 -> "10 MHz"
                    5 -> "15 MHz"
                    6 -> "20 MHz"
                    255 -> zhEn("无效", "Invalid")
                    else -> "$bw"
                }

                else -> bw.toString()
            }
        }

        fun mapBoolean(flag: Int): String = if (flag == 1) {
            appContext.getString(R.string.state_yes)
        } else {
            appContext.getString(R.string.state_no)
        }

        return QosDisplayData(
            subId = formatValue(subId),
            rat = mapRat(rat),
            accessMode = mapAccessMode(rat, endcState),
            endcState = mapEndcState(endcState, rat),
            arfcn = formatValue(arfcn),
            pci = formatValue(pci),
            band = formatValue(band),
            dlBw = mapDlBw(dlBw, rat),
            rsrp = formatValue(rsrp, "dBm"),
            rsrq = formatValue(rsrq, "dB"),
            snr = formatValue(snr, "dB"),
            svcStatus = mapSvcStatus(svcStatus),

            ulTimeStamp = formatValue(ulTimeStamp, "ms"),
            ulPdcpNumDataPdu = formatValue(ulPdcpNumDataPdu),
            ulPdcpNumDropPdu = formatValue(ulPdcpNumDropPdu),
            ulPdcpTput = formatThroughput(ulPdcpTput),
            ulRlcNumDataPdu = formatValue(ulRlcNumDataPdu),
            ulRlcRetx = formatValue(ulRlcRetx),
            ulGrant = formatValue(ulGrant),
            ulBsr = formatValue(ulBsr),
            ulBler = formatValue(ulBler, "%", setOf(-1)),

            dlTimeStamp = formatValue(dlTimeStamp, "ms"),
            dlPdcpNumDataPdu = formatValue(dlPdcpNumDataPdu),
            dlPdcpTput = formatThroughput(dlPdcpTput),
            dlRlcNumDataPdu = formatValue(dlRlcNumDataPdu),
            dlRlcRetx = formatValue(dlRlcRetx),
            dlRlcDrop = formatValue(dlRlcDrop),
            dlMacPaddingBytes = formatValue(dlMacPaddingBytes, "bytes"),
            dlPdcpNumMissToUppPdu = formatValue(dlPdcpNumMissToUppPdu),
            dlBler = formatValue(dlBler, "%", setOf(-1)),

            isDualSimConflict = mapBoolean(isDualSimConflict),
            latency = formatValue(latency, "ms", setOf(-1)),
            cellId = formatValue(cellId),
            nr5gScs = mapNr5gScs(nr5gScs),
            sub1RrcState = mapRrcState(sub1RrcState),
            sub2RrcState = mapRrcState(sub2RrcState),
            rachCount = formatValue(rachCount),
            rachAbortCount = formatValue(rachAbortCount),
            calcPower = formatDoubleValue(calcPower, 10, "dBm"),
            mtpl = formatDoubleValue(mtpl, 10, "dBm"),
            pathLoss = formatDoubleValue(pathLoss, 10, "dB"),
            fbrxCount = formatValue(fbrxCount),
            cellLoad = formatValue(cellLoad, "%", setOf(-1)),

            linkReport = if (linkReport) {
                appContext.getString(R.string.state_yes)
            } else {
                appContext.getString(R.string.state_no)
            },
            limitSpeedFlag = formatValue(limitSpeedFlag),
            limitSpeedRate = formatValue(limitSpeedRate, "kbps", setOf(0))
        )
    }
}

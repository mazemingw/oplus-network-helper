package com.nvmex.networkhelper.viewmodel.wifi

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nvmex.networkhelper.model.wifi.WifiUiModel
import com.nvmex.networkhelper.util.wifi.WifiQualityEvaluator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class WifiViewModel @Inject constructor(
    private val repo: WifiRepository
) : ViewModel() {

    private val _ui = MutableStateFlow(WifiUiModel())
    val ui: StateFlow<WifiUiModel> = _ui.asStateFlow()

    private var refreshJob: Job? = null

    // ===== 差分基线（属于 VM 状态）=====
    private var lastTsMs: Long = 0L
    private var lastTxRetries: Long? = null
    private var lastTxBad: Long? = null

    fun startRefreshing(intervalMs: Long = 1000L) {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                val raw = repo.snapshot()
                _ui.value = enrich(raw)
                delay(intervalMs)
            }
        }
    }

    fun stopRefreshing() {
        refreshJob?.cancel()
        refreshJob = null
    }

    private fun enrich(raw: WifiUiModel): WifiUiModel {
        val now = System.currentTimeMillis()

        val dtSec = if (lastTsMs <= 0L) null else ((now - lastTsMs).coerceAtLeast(1L) / 1000.0)

        // dt 太大：视为“中断后恢复”，不做 perSec（避免误判）
        val effectiveDtSec = dtSec?.takeIf { it <= 5.0 }

        val txRetryPerSec = effectiveDtSec?.let { calcRatePerSec(lastTxRetries, raw.txRetriesTotal, it) }
        val txBadPerSec = effectiveDtSec?.let { calcRatePerSec(lastTxBad, raw.txBadTotal, it) }

        // 更新基线（无论有没有 dt，都要更新）
        lastTsMs = now
        lastTxRetries = raw.txRetriesTotal
        lastTxBad = raw.txBadTotal

        val hasReliability = (txRetryPerSec != null || txBadPerSec != null)

        val quality = WifiQualityEvaluator.evaluate(
            rssiDbm = raw.rssiDbm,
            linkSpeedMbps = raw.linkSpeedMbps,
            txRetryPerSec = txRetryPerSec,
            txBadPerSec = txBadPerSec,
            freqMhz = raw.frequencyMhz,
            isDegraded = !hasReliability
        )

        return raw.copy(
            txRetryPerSec = txRetryPerSec,
            txBadPerSec = txBadPerSec,
            qualityScore = quality.score,
            qualityLabel = quality.label,
            qualityHint = quality.hint
        )
    }


    private fun calcRatePerSec(prev: Long?, curr: Long?, dtSec: Double): Double? {
        if (prev == null || curr == null) return null
        val delta = (curr - prev).coerceAtLeast(0L)
        return delta / dtSec
    }
}

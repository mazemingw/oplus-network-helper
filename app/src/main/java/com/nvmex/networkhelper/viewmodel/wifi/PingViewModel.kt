package com.nvmex.networkhelper.viewmodel.wifi

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nvmex.networkhelper.R
import com.nvmex.networkhelper.model.wifi.PingSample
import com.nvmex.networkhelper.util.wifi.PingGatewayUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class PingUiState(
    val gatewayIp: String? = null,
    val samples: List<PingSample> = emptyList(),
    val minMs: Double? = null,
    val maxMs: Double? = null,
    val avgMs: Double? = null,
    val lossRate: Double = 0.0,
    val lastError: String? = null
)

class PingViewModel(app: Application) : AndroidViewModel(app) {

    private val _ui = MutableStateFlow(PingUiState())
    val ui: StateFlow<PingUiState> = _ui.asStateFlow()

    private var job: Job? = null

    fun start(intervalMs: Long = 1000L, windowSec: Int = 60) {
        if (job?.isActive == true) return

        job = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                val ctx = getApplication<Application>().applicationContext
                val target = PingGatewayUtil.getDefaultGatewayTarget(ctx, wifiOnly = true)

                if (target == null) {
                    _ui.value = PingUiState(
                        lastError = ctx.getString(R.string.wifi_ping_disconnected)
                    )
                    delay(intervalMs)
                    continue
                }

                val gwText = target.displayText
                val rtt = PingGatewayUtil.pingOnce(target, timeoutSec = 1)
                val now = System.currentTimeMillis()

                val old = _ui.value.samples
                val newList = (old + PingSample(now, rtt)).takeLast(windowSec)

                val ok = newList.mapNotNull { it.ms }
                val min = ok.minOrNull()
                val max = ok.maxOrNull()
                val avg = if (ok.isNotEmpty()) ok.average() else null
                val loss = if (newList.isNotEmpty()) {
                    newList.count { it.ms == null }.toDouble() / newList.size.toDouble()
                } else {
                    0.0
                }

                _ui.value = _ui.value.copy(
                    gatewayIp = gwText,
                    samples = newList,
                    minMs = min,
                    maxMs = max,
                    avgMs = avg,
                    lossRate = loss,
                    lastError = if (rtt == null) ctx.getString(R.string.wifi_ping_current_timeout) else null
                )

                delay(intervalMs)
            }
        }
    }

    fun stop(reset: Boolean = false) {
        job?.cancel()
        job = null
        if (reset) {
            val ctx = getApplication<Application>().applicationContext
            _ui.value = PingUiState(
                lastError = ctx.getString(R.string.wifi_ping_disconnected)
            )
        }
    }
}

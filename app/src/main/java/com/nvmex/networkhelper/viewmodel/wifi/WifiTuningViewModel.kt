package com.nvmex.networkhelper.viewmodel.wifi

import android.app.Application
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nvmex.networkhelper.R
import com.nvmex.networkhelper.store.wifi.WifiTuningState
import com.nvmex.networkhelper.store.wifi.WifiTuningStore
import com.nvmex.networkhelper.util.shell.SuShellRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class WifiTuningUiState(
    val hiPerfEnabled: Boolean = false,
    val lowLatencyEnabled: Boolean = false,
    val hasRoot: Boolean = false,
    val busy: Boolean = false,
    val lastMsg: WifiTuningMessage? = null
)

data class WifiTuningMessage(
    @param:StringRes val resId: Int,
    val args: List<String> = emptyList()
)

class WifiTuningViewModel(app: Application) : AndroidViewModel(app) {

    private val su = SuShellRunner()
    private val store = WifiTuningStore(app.applicationContext)

    // 持久状态（开关）
    private val persisted: StateFlow<WifiTuningState> =
        store.stateFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WifiTuningState())

    // UI 状态（额外增加 busy/root/msg）
    private val _ui = kotlinx.coroutines.flow.MutableStateFlow(WifiTuningUiState())
    val ui: StateFlow<WifiTuningUiState> = _ui

    init {
        // 订阅持久状态，映射到 UI
        viewModelScope.launch {
            store.stateFlow.collect { s ->
                _ui.value = _ui.value.copy(
                    hiPerfEnabled = s.hiPerfEnabled,
                    lowLatencyEnabled = s.lowLatencyEnabled
                )
            }
        }

        // 检测 root
        viewModelScope.launch(Dispatchers.IO) {
            val ok = su.hasSu()
            _ui.value = _ui.value.copy(
                hasRoot = ok,
                lastMsg = if (!ok) {
                    WifiTuningMessage(R.string.wifi_turbo_no_root_disabled)
                } else {
                    null
                }
            )
        }
    }

    fun toggleHiPerf() {
        val target = !_ui.value.hiPerfEnabled
        runSuToggle(
            cmd = "cmd wifi force-hi-perf-mode ${if (target) "enabled" else "disabled"}",
            onSuccess = { store.setHiPerf(target) },
            successMsg = WifiTuningMessage(
                if (target) R.string.wifi_turbo_high_perf_enabled else R.string.wifi_turbo_high_perf_disabled
            ),
            failMsgRes = R.string.wifi_turbo_high_perf_failed
        )
    }

    fun toggleLowLatency() {
        val target = !_ui.value.lowLatencyEnabled
        runSuToggle(
            cmd = "cmd wifi force-low-latency-mode ${if (target) "enabled" else "disabled"}",
            onSuccess = { store.setLowLatency(target) },
            successMsg = WifiTuningMessage(
                if (target) R.string.wifi_turbo_low_latency_enabled else R.string.wifi_turbo_low_latency_disabled
            ),
            failMsgRes = R.string.wifi_turbo_low_latency_failed
        )
    }

    private fun runSuToggle(
        cmd: String,
        onSuccess: suspend () -> Unit,
        successMsg: WifiTuningMessage,
        @StringRes failMsgRes: Int
    ) {
        val now = _ui.value
        if (!now.hasRoot) {
            _ui.value = now.copy(lastMsg = WifiTuningMessage(R.string.wifi_turbo_no_root_command, listOf(cmd)))
            return
        }
        if (now.busy) return

        viewModelScope.launch {
            _ui.value = _ui.value.copy(busy = true, lastMsg = null)

            val r = withContext(Dispatchers.IO) { su.execSu(cmd, timeoutMs = 1500) }

            if (r.code == 0) {
                onSuccess()
                _ui.value = _ui.value.copy(busy = false, lastMsg = successMsg)
            } else {
                val reason = buildString {
                    append("code=").append(r.code)
                    if (r.err.isNotBlank()) append(" err=").append(r.err.trim())
                    if (r.out.isNotBlank()) append(" out=").append(r.out.trim())
                }
                _ui.value = _ui.value.copy(
                    busy = false,
                    lastMsg = WifiTuningMessage(failMsgRes, listOf(reason))
                )
            }
        }
    }
}

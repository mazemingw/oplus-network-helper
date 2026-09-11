package com.nvmex.networkhelper.iperf.client

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nvmex.networkhelper.iperf.IperfJsonParser
import com.nvmex.networkhelper.iperf.IperfSummary
import com.nvmex.networkhelper.iperf.store.IperfConfigStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject

class IperfClientViewModel(app: Application) : AndroidViewModel(app) {

    private val runner = IperfClientRunnerNative()
    private val store = IperfConfigStore(app.applicationContext)

    private val _ui = MutableStateFlow(IperfClientUiState())
    val ui: StateFlow<IperfClientUiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            store.observe().collect { cfg ->
                _ui.update { it.copy(config = cfg) }
            }
        }
    }

    fun updateConfig(block: (IperfClientConfig) -> IperfClientConfig) {
        val old = _ui.value.config
        val newCfg = block(old)

        if (newCfg == old) return // ✅关键：无变化直接返回

        _ui.update { it.copy(config = newCfg) }

        viewModelScope.launch {
            store.save(newCfg)
        }
    }




    fun start() {
        if (runner.isRunning()) return

        _ui.update { it.copy(running = true, summary = null, log = "", points = emptyList()) }

        viewModelScope.launch(Dispatchers.IO) {
            val cfg = ui.value.config
            var idx = 0

            // ✅记录 end 事件行（流式 NDJSON 的最终汇总通常在这）

            var lastEndEventLine: String? = null
            val out = runCatching {

                runner.runOnceStream(cfg) { line ->
                    // log
                    _ui.update { st -> st.copy(log = (st.log + line + "\n").takeLast(25_000)) }

                    // points
                    val p = IperfStreamParser.parseIntervalPoint(line, fallbackIndex = idx)
                    if (p != null) {
                        idx++
                        _ui.update { st -> st.copy(points = (st.points + p).takeLast(600)) }
                    }

                    // ✅稳：JSON 解析判断 end
                    runCatching {
                        val obj = JSONObject(line)
                        if (obj.optString("event") == "end") {
                            lastEndEventLine = line
                        }
                    }
                }
            }.getOrElse { e ->
                _ui.update { st ->
                    st.copy(
                        running = false,
                        summary = IperfSummary(
                            protocol = cfg.protocol,
                            seconds = cfg.durationSec.toDouble(),
                            parallel = cfg.parallel,
                            reverse = cfg.reverse,
                            error = e.message ?: "run error"
                        )
                    )
                }
                return@launch
            }

            // ✅优先用 end 事件行（因为它才是“流式场景下的最终汇总”）
            val summarySource = lastEndEventLine ?: out.jsonText

            val summary = summarySource?.let { IperfJsonParser.parseSummary(it, cfg) }
                ?: IperfSummary(
                    protocol = cfg.protocol,
                    seconds = cfg.durationSec.toDouble(),
                    parallel = cfg.parallel,
                    reverse = cfg.reverse,
                    error = "未解析到 JSON"
                )

            _ui.update { it.copy(running = false, summary = summary) }
        }
    }



    fun stop() {
        viewModelScope.launch(Dispatchers.IO) {
            runner.stop { line ->
                _ui.update { st -> st.copy(log = (st.log + line + "\n").takeLast(25_000)) }
            }
        }
    }


}
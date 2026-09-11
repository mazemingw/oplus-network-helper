package com.nvmex.networkhelper.viewmodel.menu

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nvmex.networkhelper.model.menu.LteCellQueryBody
import com.nvmex.networkhelper.model.menu.LteCellQueryResp
import com.nvmex.networkhelper.network.api.ApiService
import com.nvmex.networkhelper.network.model.NrCellQueryResp
import com.nvmex.networkhelper.network.model.NrQueryReq
import com.nvmex.networkhelper.repository.CellParamLocalRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface CellQueryUiState {
    data object Idle : CellQueryUiState
    data object Loading : CellQueryUiState
    data class LteSuccess(val resp: LteCellQueryResp) : CellQueryUiState
    data class NrSuccess(val resp: NrCellQueryResp) : CellQueryUiState
    data class Error(val msg: String) : CellQueryUiState
}

@HiltViewModel
class MenuCellQueryViewModel @Inject constructor(
    private val api: ApiService,
    private val localRepository: CellParamLocalRepository
) : ViewModel() {

    private val _ui = MutableStateFlow<CellQueryUiState>(CellQueryUiState.Idle)
    val ui: StateFlow<CellQueryUiState> = _ui

    private var lastKey: String? = null
    private var debounceJob: Job? = null

    fun query(body: LteCellQueryBody) {
        queryLte(body)
    }

    fun queryDebounced(body: LteCellQueryBody, debounceMs: Long = 600L) {
        queryLteDebounced(body, debounceMs)
    }

    fun queryLte(body: LteCellQueryBody) {
        queryLte(body, forceRemote = false)
    }

    fun queryLteForce(body: LteCellQueryBody) {
        queryLte(body, forceRemote = true)
    }

    private fun queryLte(body: LteCellQueryBody, forceRemote: Boolean) {
        val key = buildLteKey(body) ?: run {
            _ui.value = CellQueryUiState.Error("LTE 查询参数为空")
            return
        }

        viewModelScope.launch {
            if (!forceRemote) {
                val local = localRepository.queryLte(body)
                if (local != null) {
                    lastKey = key
                    _ui.value = CellQueryUiState.LteSuccess(local)
                    return@launch
                }
            }

            if (!forceRemote && key == lastKey && _ui.value is CellQueryUiState.LteSuccess) {
                return@launch
            }
            lastKey = key

            _ui.value = CellQueryUiState.Loading
            runCatching { api.queryLteCellParams(body) }
                .onSuccess { response ->
                    if (response.isSuccessful) {
                        val data = response.body()
                        if (data != null) {
                            if (data.data.isNotEmpty()) {
                                localRepository.saveLteQueryResp(data)
                            } else if (forceRemote) {
                                localRepository.evictLteByEci(body.eci)
                            }
                            _ui.value = CellQueryUiState.LteSuccess(data)
                        } else {
                            _ui.value = CellQueryUiState.Error("LTE 响应为空(body=null)")
                        }
                    } else {
                        _ui.value = CellQueryUiState.Error("LTE HTTP ${response.code()} ${response.message()}")
                    }
                }
                .onFailure { error ->
                    _ui.value = CellQueryUiState.Error(error.message ?: "LTE 请求失败")
                }
        }
    }

    fun queryLteDebounced(body: LteCellQueryBody, debounceMs: Long = 600L) {
        debounceJob?.cancel()
        debounceJob = viewModelScope.launch {
            delay(debounceMs)
            queryLte(body)
        }
    }

    fun queryNr(body: NrQueryReq) {
        queryNr(body, forceRemote = false)
    }

    fun queryNrForce(body: NrQueryReq) {
        queryNr(body, forceRemote = true)
    }

    private fun queryNr(body: NrQueryReq, forceRemote: Boolean) {
        Log.d("NR_QUERY", body.toString())

        val key = buildNrKey(body) ?: run {
            _ui.value = CellQueryUiState.Error("NR 查询参数为空")
            return
        }

        viewModelScope.launch {
            if (!forceRemote) {
                val local = localRepository.queryNr(body)
                if (local != null) {
                    lastKey = key
                    _ui.value = CellQueryUiState.NrSuccess(local)
                    return@launch
                }
            }

            if (!forceRemote && key == lastKey && _ui.value is CellQueryUiState.NrSuccess) {
                return@launch
            }
            lastKey = key

            _ui.value = CellQueryUiState.Loading
            runCatching { api.queryNrCellParams(body) }
                .onSuccess { response ->
                    if (response.isSuccessful) {
                        val data = response.body()
                        if (data != null) {
                            if (data.data.isNotEmpty()) {
                                localRepository.saveNrQueryResp(data)
                            } else if (forceRemote) {
                                localRepository.evictNrByGcellId(body.gcellId)
                            }
                            _ui.value = CellQueryUiState.NrSuccess(data)
                        } else {
                            _ui.value = CellQueryUiState.Error("NR 响应为空(body=null)")
                        }
                    } else {
                        _ui.value = CellQueryUiState.Error("NR HTTP ${response.code()} ${response.message()}")
                    }
                }
                .onFailure { error ->
                    _ui.value = CellQueryUiState.Error(error.message ?: "NR 请求失败")
                }
        }
    }

    fun queryNrDebounced(body: NrQueryReq, debounceMs: Long = 600L) {
        debounceJob?.cancel()
        debounceJob = viewModelScope.launch {
            delay(debounceMs)
            queryNr(body)
        }
    }

    fun reset() {
        debounceJob?.cancel()
        debounceJob = null
        lastKey = null
        _ui.value = CellQueryUiState.Idle
    }

    fun clearCache() {
        viewModelScope.launch {
            localRepository.clearAll()
        }
    }

    private fun buildLteKey(body: LteCellQueryBody): String? {
        body.eci?.let { return "LTE|ECI|$it" }
        return "LTE|${body.tac}|${body.earfcn}|${body.pci}|${body.cell_id}"
    }

    private fun buildNrKey(body: NrQueryReq): String? {
        val gcellId = body.gcellId?.trim().takeUnless { it.isNullOrBlank() }
        if (!gcellId.isNullOrBlank()) return "NR|GCELL|$gcellId"

        if (body.nrTac == null && body.nrArfcn == null && body.nrPci == null) return null
        return "NR|${body.nrTac}|${body.nrArfcn}|${body.nrPci}"
    }
}

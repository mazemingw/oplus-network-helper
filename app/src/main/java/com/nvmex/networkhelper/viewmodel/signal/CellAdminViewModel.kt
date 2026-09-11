package com.nvmex.networkhelper.viewmodel.signal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nvmex.networkhelper.network.api.ApiService
import com.nvmex.networkhelper.network.base.ApiResult
import com.nvmex.networkhelper.network.base.safeApiCall
import com.nvmex.networkhelper.network.model.LteCellAdminDeleteReq
import com.nvmex.networkhelper.network.model.LteCellAdminUpdateReq
import com.nvmex.networkhelper.network.model.NrCellAdminDeleteReq
import com.nvmex.networkhelper.network.model.NrCellAdminUpdateReq
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CellAdminUiState(
    val isSubmitting: Boolean = false,
    val errorMessage: String? = null,
    val successMessage: String? = null,
    val lastAction: String? = null,
    val lastActionAt: Long? = null
)

@HiltViewModel
class CellAdminViewModel @Inject constructor(
    private val apiService: ApiService
) : ViewModel() {

    private val _uiState = MutableStateFlow(CellAdminUiState())
    val uiState: StateFlow<CellAdminUiState> = _uiState.asStateFlow()

    fun updateLte(req: LteCellAdminUpdateReq) {
        submit("update_lte", "LTE 已更新") { apiService.updateLteCellParams(req) }
    }

    fun deleteLte(req: LteCellAdminDeleteReq) {
        submit("delete_lte", "LTE 已删除") { apiService.deleteLteCellParams(req) }
    }

    fun updateNr(req: NrCellAdminUpdateReq) {
        submit("update_nr", "NR 已更新") { apiService.updateNrCellParams(req) }
    }

    fun deleteNr(req: NrCellAdminDeleteReq) {
        submit("delete_nr", "NR 已删除") { apiService.deleteNrCellParams(req) }
    }

    fun consumeActionEvent() {
        _uiState.update { it.copy(lastAction = null, lastActionAt = null) }
    }

    fun clearMessages() {
        _uiState.update { it.copy(errorMessage = null, successMessage = null) }
    }

    private fun submit(
        action: String,
        successMessage: String,
        request: suspend () -> retrofit2.Response<com.nvmex.networkhelper.network.model.CellAdminActionResp>
    ) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isSubmitting = true,
                    errorMessage = null,
                    successMessage = null
                )
            }

            when (val result = safeApiCall(request)) {
                is ApiResult.Ok -> {
                    if (result.data.ok) {
                        _uiState.update {
                            it.copy(
                                isSubmitting = false,
                                errorMessage = null,
                                successMessage = successMessage,
                                lastAction = action,
                                lastActionAt = System.currentTimeMillis()
                            )
                        }
                    } else {
                        _uiState.update {
                            it.copy(
                                isSubmitting = false,
                                errorMessage = "操作失败",
                                successMessage = null
                            )
                        }
                    }
                }

                is ApiResult.HttpError -> {
                    val msg = when (result.code) {
                        401 -> "鉴权失效，请重新验证开发者密码"
                        403 -> "仅超级管理员可执行此操作"
                        404 -> "目标记录不存在"
                        else -> "操作失败（HTTP ${result.code}）"
                    }
                    _uiState.update {
                        it.copy(
                            isSubmitting = false,
                            errorMessage = msg,
                            successMessage = null
                        )
                    }
                }

                is ApiResult.NetworkError -> {
                    _uiState.update {
                        it.copy(
                            isSubmitting = false,
                            errorMessage = result.message.ifBlank { "网络异常" },
                            successMessage = null
                        )
                    }
                }
            }
        }
    }
}


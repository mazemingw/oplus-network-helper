package com.nvmex.networkhelper.viewmodel.signal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nvmex.networkhelper.network.api.ApiService
import com.nvmex.networkhelper.network.base.ApiResult
import com.nvmex.networkhelper.network.base.safeApiCall
import com.nvmex.networkhelper.network.model.DevAuthVerifyReq
import com.nvmex.networkhelper.util.home.DeveloperModePrefs
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DeveloperAuthUiState(
    val isVerifying: Boolean = false,
    val errorMessage: String? = null,
    val verifiedRole: DeveloperModePrefs.Role? = null,
    val authToken: String? = null,
    val tokenExpiresAtMs: Long? = null
)

@HiltViewModel
class DeveloperAuthViewModel @Inject constructor(
    private val apiService: ApiService
) : ViewModel() {

    private val _uiState = MutableStateFlow(DeveloperAuthUiState())
    val uiState: StateFlow<DeveloperAuthUiState> = _uiState.asStateFlow()

    fun verifyPassword(password: String) {
        val input = password.trim()
        if (input.isBlank()) {
            _uiState.update {
                it.copy(
                    isVerifying = false,
                    errorMessage = "请输入开发者密码",
                    verifiedRole = null,
                    authToken = null,
                    tokenExpiresAtMs = null
                )
            }
            return
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isVerifying = true,
                    errorMessage = null,
                    verifiedRole = null,
                    authToken = null,
                    tokenExpiresAtMs = null
                )
            }

            when (val result = safeApiCall { apiService.verifyDevAuthPassword(DevAuthVerifyReq(input)) }) {
                is ApiResult.Ok -> {
                    val role = when (result.data.role?.trim()?.lowercase()) {
                        DeveloperModePrefs.Role.SUPER_ADMIN.value -> DeveloperModePrefs.Role.SUPER_ADMIN
                        DeveloperModePrefs.Role.DEVELOPER.value -> DeveloperModePrefs.Role.DEVELOPER
                        else -> null
                    }

                    val token = result.data.auth_token?.trim().orEmpty()
                    val exp = result.data.expires_at_ms

                    if (result.data.ok && role != null && token.isNotBlank() && exp != null && exp > 0L) {
                        _uiState.update {
                            it.copy(
                                isVerifying = false,
                                errorMessage = null,
                                verifiedRole = role,
                                authToken = token,
                                tokenExpiresAtMs = exp
                            )
                        }
                    } else {
                        _uiState.update {
                            it.copy(
                                isVerifying = false,
                                errorMessage = "密码验证失败",
                                verifiedRole = null,
                                authToken = null,
                                tokenExpiresAtMs = null
                            )
                        }
                    }
                }

                is ApiResult.HttpError -> {
                    _uiState.update {
                        it.copy(
                            isVerifying = false,
                            errorMessage = if (result.code == 401) "密码错误" else "验证失败（HTTP ${result.code}）",
                            verifiedRole = null,
                            authToken = null,
                            tokenExpiresAtMs = null
                        )
                    }
                }

                is ApiResult.NetworkError -> {
                    _uiState.update {
                        it.copy(
                            isVerifying = false,
                            errorMessage = result.message.ifBlank { "网络异常" },
                            verifiedRole = null,
                            authToken = null,
                            tokenExpiresAtMs = null
                        )
                    }
                }
            }
        }
    }

    fun consumeVerifiedRole() {
        _uiState.update {
            it.copy(
                verifiedRole = null,
                authToken = null,
                tokenExpiresAtMs = null
            )
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }
}

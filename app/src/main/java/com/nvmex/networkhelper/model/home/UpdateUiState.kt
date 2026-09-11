package com.nvmex.networkhelper.model.home


import com.nvmex.networkhelper.network.model.VersionUpdateResponse

sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data object Checking : UpdateUiState

    data class Available(
        val info: VersionUpdateResponse,
        val mandatory: Boolean
    ) : UpdateUiState

    data class NoUpdate(val reason: String = "") : UpdateUiState
    data class Error(val msg: String) : UpdateUiState
}

package com.nvmex.networkhelper.model.home


import com.nvmex.networkhelper.network.model.VersionUpdateResponse

sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data object Checking : UpdateUiState

    data class Available(
        val info: VersionUpdateResponse,
        val mandatory: Boolean,
        val phase: UpdatePhase = UpdatePhase.READY,
        val error: String? = null,
        val signatureMismatchAccepted: Boolean = false
    ) : UpdateUiState

    data class NoUpdate(val reason: String = "") : UpdateUiState
    data class Error(val msg: String) : UpdateUiState
}

enum class UpdatePhase {
    READY, DOWNLOADING, VERIFYING, OPENING_INSTALLER, READY_TO_INSTALL, SIGNATURE_MISMATCH, AWAITING_PERMISSION, INSTALLER_LAUNCHED;

    val isBusy: Boolean
        get() = this == DOWNLOADING || this == VERIFYING || this == OPENING_INSTALLER
}

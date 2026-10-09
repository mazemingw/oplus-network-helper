package com.nvmex.networkhelper.util.home

import com.nvmex.networkhelper.model.home.UpdatePhase
import com.nvmex.networkhelper.model.home.UpdateUiState
import com.nvmex.networkhelper.network.model.VersionUpdateResponse
import org.junit.Assert.*
import org.junit.Test

class UpdateApkValidationTest {
    private fun validate(
        name: String = "app", version: Long = 47,
        signers: Set<String> = setOf("a"), history: Set<String> = signers,
        installed: Set<String> = setOf("a")
    ) = validateUpdateApk(name, "app", version, 47, 46, signers, history, installed)

    @Test fun acceptsMatchingSignatureAndValidRotationLineage() {
        assertEquals(UpdateApkCompatibility.COMPATIBLE, validate())
        assertEquals(UpdateApkCompatibility.COMPATIBLE, validate(signers = setOf("b"), history = setOf("a", "b")))
    }

    @Test fun rejectsWrongPackageVersionAndMissingSignature() {
        assertThrows(IllegalArgumentException::class.java) { validate(name = "other") }
        assertThrows(IllegalArgumentException::class.java) { validate(version = 46) }
        assertThrows(IllegalArgumentException::class.java) { validate(version = 48) }
        assertThrows(IllegalArgumentException::class.java) { validate(signers = emptySet()) }
    }

    @Test fun requiresExactSignerSetForMultiSignedApps() {
        assertEquals(UpdateApkCompatibility.COMPATIBLE, validate(signers = setOf("a", "b"), installed = setOf("a", "b")))
        assertEquals(UpdateApkCompatibility.SIGNATURE_MISMATCH, validate(signers = setOf("a", "b")))
        assertEquals(UpdateApkCompatibility.SIGNATURE_MISMATCH,
            validate(signers = setOf("a"), history = setOf("a", "b"), installed = setOf("a", "b")))
    }

    @Test fun signatureMismatchIsContinuableButDoesNotRelaxMetadataChecks() {
        assertEquals(UpdateApkCompatibility.SIGNATURE_MISMATCH, validate(signers = setOf("other")))
        assertThrows(IllegalArgumentException::class.java) { validate(name = "other", signers = setOf("other")) }
        assertThrows(IllegalArgumentException::class.java) { validate(version = 48, signers = setOf("other")) }
        assertThrows(IllegalArgumentException::class.java) { validate(installed = emptySet()) }
    }

    @Test fun acceptedSignatureMismatchSurvivesPermissionAndInstallerHandoff() {
        val accepted = UpdateUiState.Available(
            VersionUpdateResponse(versionCode = 47, versionName = "x", updateContent = "test"), false,
            phase = UpdatePhase.SIGNATURE_MISMATCH, signatureMismatchAccepted = true
        )
        assertTrue(accepted.copy(phase = UpdatePhase.AWAITING_PERMISSION).signatureMismatchAccepted)
        assertTrue(accepted.copy(phase = UpdatePhase.INSTALLER_LAUNCHED).signatureMismatchAccepted)
        assertFalse(accepted.phase.isBusy)
    }

    @Test fun onlyRealWorkLocksButtonAndFailureAndInstallerHandoffAllowRetry() {
        val available = UpdateUiState.Available(VersionUpdateResponse(versionCode = 47, versionName = "x", updateContent = "test"), false)
        assertTrue(available.copy(phase = UpdatePhase.DOWNLOADING).phase.isBusy)
        assertTrue(available.copy(phase = UpdatePhase.VERIFYING).phase.isBusy)
        assertTrue(available.copy(phase = UpdatePhase.OPENING_INSTALLER).phase.isBusy)
        for (phase in listOf(UpdatePhase.READY, UpdatePhase.AWAITING_PERMISSION, UpdatePhase.INSTALLER_LAUNCHED, UpdatePhase.READY_TO_INSTALL, UpdatePhase.SIGNATURE_MISMATCH)) {
            assertFalse(available.copy(phase = phase).phase.isBusy)
        }
        assertFalse(available.copy(error = "failure").phase.isBusy)
    }
}

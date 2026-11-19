package com.nvmex.networkhelper.util.modem

import com.nvmex.networkhelper.util.qmi.QmiBand
import com.nvmex.networkhelper.util.qmi.QmiDeviceState
import com.nvmex.networkhelper.util.qmi.QmiProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MobileLockStatusTest {
    private val hardware = mapOf(QmiBand.LTE to setOf(1, 3), QmiBand.NR_SA to setOf(41, 78))
    private val unlocked = QmiDeviceState(2, QmiProfile(setOf("lte", "nr"), hardware, "both"), hardware, emptyMap())

    @Test fun oneRestrictedMaskIsEnoughEvenWhenAnotherMaskCannotBeRead() {
        val restricted = unlocked.copy(profile = unlocked.profile.copy(bands = mapOf(QmiBand.LTE to setOf(3))))
        assertEquals(true, restricted.bandMaskLocked())
        assertEquals(false, unlocked.bandMaskLocked())
        assertNull(restricted.copy(profile = restricted.profile.copy(bands = mapOf(QmiBand.LTE to setOf(1, 3)))).bandMaskLocked())
        assertNull(unlocked.copy(controlReady = false).bandMaskLocked())
    }

    @Test fun recoveryClearsEveryActiveCellFamilyAndUsesSavedLocksOnlyForMissingReadback() {
        val partial = unlocked.copy(locks = mapOf("lte" to "", "nr" to null, "plmn" to "460-01"))
        assertEquals(setOf("nr", "plmn"), partial.cellLocksToClear(setOf(MobileLock.LTE_CELL, MobileLock.NR_CELL)))
        assertEquals(setOf("plmn"), partial.cellLocksToClear(emptySet()))
    }
}

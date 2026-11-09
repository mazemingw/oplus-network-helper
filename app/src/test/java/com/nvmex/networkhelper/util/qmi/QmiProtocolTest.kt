package com.nvmex.networkhelper.util.qmi

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class QmiProtocolTest {
    private val hardware = QmiBand.entries.associateWith { domain ->
        when (domain) { QmiBand.GSM -> setOf(850, 900); QmiBand.WCDMA -> setOf(1, 8); QmiBand.LTE -> setOf(1, 3, 66); else -> setOf(41, 78) }
    }
    private val profile = QmiProfile(setOf("gsm", "wcdma", "lte", "nr"), hardware, "both")

    @Test fun selectionsStartEmptyWithoutChangingModemState() {
        val selection = qmiEmptyBandSelection(profile)
        assertTrue(selection.bands.values.all { it.isEmpty() })
        assertEquals(profile.rats, selection.rats)
        assertEquals(profile, qmiSelectedBandTarget(profile, selection))
        assertTrue(profile.bands.values.all { it.isNotEmpty() })
    }

    @Test fun selectingOneRatNeverClearsUnselectedDomains() {
        val selection = qmiEmptyBandSelection(profile).copy(bands = mapOf(QmiBand.LTE to setOf(3)))
        val target = qmiSelectedBandTarget(profile, selection)
        assertEquals(setOf(3), target.bands[QmiBand.LTE])
        assertEquals(profile.bands[QmiBand.NR_SA], target.bands[QmiBand.NR_SA])
        assertEquals(profile.bands[QmiBand.GSM], target.bands[QmiBand.GSM])
        assertEquals("lte_set", qmiBandChanges(profile, target, hardware, false).single().getString("cmd"))
    }

    @Test fun emptySelectionsCanStillChangeRatOrNrMode() {
        val selection = qmiEmptyBandSelection(profile).copy(nrMode = "sa")
        val target = qmiSelectedBandTarget(profile, selection)
        assertEquals("mode_set", qmiBandChanges(profile, target, hardware, false).single().getString("cmd"))
    }

    @Test fun resettingSelectionsClearsAFormerLockChoice() {
        val selected = profile.copy(bands = hardware + (QmiBand.NR_SA to setOf(78)))
        assertTrue(qmiEmptyBandSelection(selected).bands.values.all { it.isEmpty() })
    }

    @Test fun unlockFromLteOnlyRestoresAllHardwareRatsAfterBandMasks() {
        val locked = profile.copy(rats = setOf("lte"), bands = hardware + (QmiBand.LTE to setOf(3)))
        val plan = qmiBandUnlockPlan(locked, hardware)
        assertEquals(listOf("reset", "rat_set"), plan.commands.map { it.getString("cmd") })
        assertEquals("gsm,lte,nr,wcdma", plan.commands.last().getString("rat"))
        assertEquals(profile, plan.target)
        assertTrue(66 in plan.target.bands.getValue(QmiBand.LTE))
    }

    @Test fun unlockAlsoRemovesSaOnlyOrNsaOnlyRestrictionBeforeEnablingRats() {
        listOf("sa", "nsa").forEach { mode ->
            val plan = qmiBandUnlockPlan(profile.copy(rats = setOf("lte"), nrMode = mode), hardware)
            assertEquals(listOf("reset", "mode_set", "rat_set"), plan.commands.map { it.getString("cmd") })
            assertEquals("both", plan.commands[1].getString("mode"))
            assertEquals("both", plan.target.nrMode)
        }
    }

    @Test fun unlockNeverEnablesHardwareAbsentRatsOrWritesAnUnsupportedNrMode() {
        val lteHardware = QmiBand.entries.associateWith { if (it == QmiBand.LTE) setOf(1, 3) else emptySet() }
        val plan = qmiBandUnlockPlan(profile.copy(rats = setOf("lte"), nrMode = "unknown"), lteHardware)
        assertEquals(setOf("lte"), plan.target.rats)
        assertEquals(listOf("reset", "rat_set"), plan.commands.map { it.getString("cmd") })
        assertEquals("lte", plan.commands.last().getString("rat"))
        assertEquals("unknown", plan.target.nrMode)
    }

    @Test fun unlockDoesNotProbeUnavailableNrModeWithASetter() {
        val plan = qmiBandUnlockPlan(profile.copy(rats = setOf("lte"), nrMode = "unknown"), hardware)
        assertEquals(listOf("reset", "rat_set"), plan.commands.map { it.getString("cmd") })
        assertEquals("unknown", plan.target.nrMode)
    }

    @Test fun unrestrictedUnlockRestoresRatWithoutRedundantNrModeSetter() {
        val plan = qmiBandUnlockPlan(profile, hardware)
        assertEquals(listOf("reset", "rat_set"), plan.commands.map { it.getString("cmd") })
        assertEquals("gsm,lte,nr,wcdma", plan.commands.last().getString("rat"))
    }

    @Test fun readOnlyNrModeStillAllowsBandAndRatUnlock() {
        val plan = qmiBandUnlockPlan(profile.copy(nrMode = "nsa"), hardware, nrModeSettable = false)
        assertEquals("nsa", plan.target.nrMode)
        assertEquals(listOf("reset", "rat_set"), plan.commands.map { it.getString("cmd") })
        assertEquals(profile.rats, plan.target.rats)
    }

    @Test fun readOnlyNrModeOnlyAllowsCoupledNrLockWhenAlreadySa() {
        val input = QmiCellInput(QmiCellMode.NR_PCI)
        rejects { qmiCellRatCommands(input, profile, nrModeSettable = false) }
        val commands = qmiCellRatCommands(input, profile.copy(nrMode = "sa"), nrModeSettable = false)
        assertEquals("rat_set", commands.single().getString("cmd"))
        assertEquals("nr", commands.single().getString("rat"))
    }

    @Test fun unlockRejectsMissingHardwareCapabilities() {
        rejects { qmiBandUnlockPlan(profile, emptyMap()) }
        rejects { qmiBandUnlockPlan(profile, QmiBand.entries.associateWith { emptySet() }) }
    }

    @Test fun groupsFollowRequestedReverseOrder() {
        assertEquals(listOf("NR SA", "NR NSA", "LTE", "WCDMA", "GSM"), qmiBandDisplayOrder.map { it.label })
    }

    @Test fun rxBarsUseFiniteClampedFractions() {
        assertEquals(0.5f, qmiRxMetricFraction(-100.0, -140.0, -60.0)!!, 0.001f)
        assertEquals(0f, qmiRxMetricFraction(-200.0, -140.0, -60.0)!!, 0f)
        assertEquals(1f, qmiRxMetricFraction(10.0, -140.0, -60.0)!!, 0f)
        assertNull(qmiRxMetricFraction(null, -140.0, -60.0))
        assertNull(qmiRxMetricFraction(Double.NaN, -140.0, -60.0))
        assertNull(qmiRxMetricFraction(Double.POSITIVE_INFINITY, -140.0, -60.0))
    }

    @Test fun failedBindingStillOffersHardwareButDisablesControl() {
        val partial = QmiDeviceState.hardwareOnly(JSONObject("""{"valid":false,"sim":1,"hardware":{"lte":[1,3],"nr":[78]}}"""), 2)!!
        assertFalse(partial.controlReady)
        assertEquals(2, partial.sim)
        assertEquals(setOf(1, 3), partial.hardware!![QmiBand.LTE])
        assertTrue(partial.profile.rats.isEmpty())
        assertTrue(partial.profile.bands.values.all { it.isEmpty() })
        assertNull(QmiDeviceState.hardwareOnly(JSONObject("""{"valid":false}"""), 1))
    }

    @Test fun unlockTargetsOnlyTheSelectedCellLockFamily() {
        assertEquals("lte_cell_lock_clear", qmiCellClearCommand(QmiCellMode.LTE))
        assertEquals("plmn_lock_clear", qmiCellClearCommand(QmiCellMode.PLMN))
        assertEquals("nr_cell_lock_clear", qmiCellClearCommand(QmiCellMode.NR_GNB))
        assertEquals("nr_cell_lock_clear", qmiCellClearCommand(QmiCellMode.NR_ARFCN))
    }

    @Test fun changingLteDoesNotRewriteRatOrNr() {
        val after = profile.copy(bands = profile.bands + (QmiBand.LTE to setOf(3, 66)))
        val commands = qmiBandChanges(profile, after, hardware, false)
        assertEquals(listOf("lte_set"), commands.map { it.getString("cmd") })
        assertEquals("[3,66]", commands.single().getJSONArray("bands").toString())
    }

    @Test fun independentSaDoesNotChangeNsa() {
        val after = profile.copy(bands = profile.bands + (QmiBand.NR_SA to setOf(78)))
        assertEquals("nr_sa_set", qmiBandChanges(profile, after, hardware, false).single().getString("cmd"))
    }

    @Test fun combinedNrUsesCombinedCommand() {
        val after = profile.copy(bands = profile.bands + mapOf(QmiBand.NR_SA to setOf(78), QmiBand.NR_NSA to setOf(78)))
        assertEquals("nr_set", qmiBandChanges(profile, after, hardware, true).single().getString("cmd"))
    }

    @Test fun unchangedProfileDoesNotSendWrites() {
        assertTrue(qmiBandChanges(profile, profile, hardware, false).isEmpty())
    }

    @Test fun changingRatRestoresKnownMasksAfterTheRatWrite() {
        val before = profile.copy(bands = profile.bands + (QmiBand.LTE to setOf(3)))
        val after = before.copy(rats = setOf("lte"))
        val commands = qmiBandChanges(before, after, hardware, false)
        assertEquals(listOf("rat_set", "lte_set"), commands.map { it.getString("cmd") })
        assertEquals(3, commands[1].getJSONArray("bands").getInt(0))
    }

    @Test fun unsupportedBandIsRejectedBeforeWriting() {
        rejects { qmiBandChanges(profile, profile.copy(bands = hardware + (QmiBand.LTE to setOf(999))), hardware, false) }
    }

    @Test fun enabledRatCannotHaveEmptyBands() {
        rejects { qmiBandChanges(profile, profile.copy(bands = hardware + (QmiBand.LTE to emptySet())), hardware, false) }
        rejects { qmiBandChanges(profile, profile.copy(rats = emptySet()), hardware, false) }
    }

    @Test fun unusedNrDomainCanBeEmpty() {
        val after = profile.copy(nrMode = "sa", bands = hardware + (QmiBand.NR_NSA to emptySet()))
        assertEquals(listOf("nr_nsa_set", "mode_set"), qmiBandChanges(profile, after, hardware, false).map { it.getString("cmd") })
    }

    @Test fun unknownModeIsNotAutomaticallyReplaced() {
        val before = profile.copy(nrMode = "unknown")
        val after = before.copy(bands = hardware + (QmiBand.LTE to setOf(3)))
        assertEquals("lte_set", qmiBandChanges(before, after, hardware, false).single().getString("cmd"))
    }

    @Test fun unreportedNrMasksDoNotBlockAnUnrelatedLteUpdate() {
        val before = profile.copy(bands = hardware + mapOf(QmiBand.NR_SA to emptySet(), QmiBand.NR_NSA to emptySet()))
        val after = before.copy(bands = before.bands + (QmiBand.LTE to setOf(3)))
        assertEquals("lte_set", qmiBandChanges(before, after, hardware, false).single().getString("cmd"))
    }

    @Test fun missingHardwareDoesNotInventCapabilities() {
        val state = JSONObject("""{"sim":1,"valid":true,"rat":{"lte":true},"lte":[3],"hardware":null}""")
        val parsed = QmiDeviceState.parse(state)
        assertNull(parsed.hardware)
        assertEquals(setOf(3), parsed.profile.bands[QmiBand.LTE])
        assertNull(parsed.locks["lte"])
    }

    @Test fun unrestrictedMasksAreIntersectedWithRealHardware() {
        val state = JSONObject("""{"sim":1,"valid":true,"rat":{"lte":true},"lte":[1,2,3,4,5],"hardware":{"lte":[1,3]}}""")
        assertEquals(setOf(1, 3), QmiDeviceState.parse(state).profile.bands[QmiBand.LTE])
    }

    @Test fun lteMultiPciSupportsZeroAndDeduplicates() {
        val command = qmiCellCommand(QmiCellInput(channel = "0", pcis = "0, 503,0"))
        assertEquals("lte_cell_lock_multi_pci_set", command.getString("cmd"))
        assertEquals("[0,503]", command.getJSONArray("pci_list").toString())
    }

    @Test fun invalidCellInputsCannotOverflowNativeIntegers() {
        rejects { qmiCellCommand(QmiCellInput(channel = "4294967296", pcis = "1")) }
        rejects { qmiCellCommand(QmiCellInput(channel = "100", pcis = "504")) }
        rejects { qmiCellCommand(QmiCellInput(channel = "100", pcis = "-1")) }
        rejects { qmiCellCommand(QmiCellInput(mode = QmiCellMode.NR_PCI, channel = "640000", band = "78", pcis = "1008")) }
        rejects { qmiCellCommand(QmiCellInput(mode = QmiCellMode.NR_ARFCN, channel = "640000", scs = 42)) }
    }

    @Test fun nrFrequencyDoesNotRequirePciOrBand() {
        val command = qmiCellCommand(QmiCellInput(mode = QmiCellMode.NR_ARFCN, channel = "640000", scs = 30))
        assertFalse(command.has("pci")); assertFalse(command.has("band"))
        assertEquals("nr_cell_lock_arfcn_set", command.getString("cmd"))
    }

    @Test fun unsignedGnbIdsAndBitWidthAreValidated() {
        val command = qmiCellCommand(QmiCellInput(mode = QmiCellMode.NR_GNB, gnbBits = "32", gnbIds = "4294967295"))
        assertEquals(4294967295L, command.getJSONArray("gnb_ids").getLong(0))
        rejects { qmiCellCommand(QmiCellInput(mode = QmiCellMode.NR_GNB, gnbBits = "22", gnbIds = "4194304")) }
        rejects { qmiCellCommand(QmiCellInput(mode = QmiCellMode.NR_GNB, gnbBits = "33", gnbIds = "1")) }
    }

    @Test fun plmnMustHaveFiveOrSixDecimalDigits() {
        val command = qmiCellCommand(QmiCellInput(mode = QmiCellMode.PLMN, plmn = "46001"))
        assertEquals(460, command.getInt("mcc")); assertEquals(1, command.getInt("mnc"))
        rejects { qmiCellCommand(QmiCellInput(mode = QmiCellMode.PLMN, plmn = "460")) }
    }

    @Test fun physicalSlotTwoCanBePrimarySubscription() {
        val sessions = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(0x100).putShort(-1).putShort(-1).putShort(-1).array()
        val bindings = QmiSimBindings.decode(frame(0x002f, 0x10 to sessions))
        assertEquals(1, bindings.subscription(2))
        rejects { bindings.subscription(1) }
    }

    @Test fun dualSimBindingsKeepPhysicalSlotsSeparate() {
        val sessions = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(0).putShort(-1).putShort(0x100).putShort(-1).array()
        val bindings = QmiSimBindings.decode(frame(0x002f, 0x10 to sessions))
        assertEquals(1, bindings.subscription(1)); assertEquals(2, bindings.subscription(2))
    }

    @Test fun allFourRxTagsAndSignedMeasurementsAreDecoded() {
        val chains = QmiRxDecoder.decode(frame(0x005a, *listOf(0x10, 0x11, 0x15, 0x16).map {
            it to chain(true, -950, -1050, 12345)
        }.toTypedArray()), "NR")
        assertEquals(listOf(0, 1, 2, 3), chains.map { it.index })
        assertEquals(-95.0, chains[0].power!!, 0.001)
        assertEquals(-105.0, chains[0].rsrp!!, 0.001)
        assertEquals(123.45, chains[0].phase!!, 0.001)
        assertNull(chains[0].ecio)
    }

    @Test fun untunedAndSentinelValuesNeverBecomeZeroMeasurements() {
        val untuned = QmiRxDecoder.decode(frame(0x005a, 0x10 to chain(false, -900, -1000)), "LTE").single()
        assertFalse(untuned.tuned); assertNull(untuned.power); assertNull(untuned.rsrp); assertNull(untuned.phase)
        val missing = QmiRxDecoder.decode(frame(0x005a, 0x15 to chain(true, Int.MIN_VALUE, -2000, -1)), "LTE").single()
        assertEquals(2, missing.index); assertNull(missing.power); assertNull(missing.rsrp); assertNull(missing.phase)
        val zeroPower = QmiRxDecoder.decode(frame(0x005a, 0x10 to chain(true, 0, -1000)), "LTE").single()
        assertNull(zeroPower.power)
        assertEquals(-100.0, zeroPower.rsrp!!, 0.001)
        assertEquals(0.0, zeroPower.phase!!, 0.001)
    }

    @Test fun malformedOrRejectedRxFramesAreRejected() {
        val good = frame(0x005a, 0x10 to chain(true, -900, -1000))
        rejects { QmiRxDecoder.decode(good.dropLast(2), "LTE") }
        rejects { QmiRxDecoder.decode("0g", "LTE") }
        rejects { QmiRxDecoder.decode(frame(0x005b), "LTE") }
        rejects { QmiRxDecoder.decode(frame(0x005a, 0x10 to ByteArray(20)), "LTE") }
        rejects { QmiRxDecoder.decode(frame(0x005a, 2 to byteArrayOf(1, 0, 3, 0)), "LTE") }
    }

    private fun rejects(block: () -> Unit) {
        try { block(); fail("Expected validation failure") } catch (_: IllegalArgumentException) { }
    }

    private fun chain(tuned: Boolean, power: Int, rsrp: Int, phase: Int = 0): ByteArray = ByteBuffer.allocate(21)
        .order(ByteOrder.LITTLE_ENDIAN).put(if (tuned) 1.toByte() else 0.toByte())
        .putInt(power).putInt(-80).putInt(-900).putInt(rsrp).putInt(phase).array()

    private fun frame(message: Int, vararg payloads: Pair<Int, ByteArray>): String {
        val tlvs = linkedMapOf(2 to ByteArray(4)).apply { payloads.forEach { put(it.first, it.second) } }
        val size = tlvs.values.sumOf { 3 + it.size }
        val b = ByteBuffer.allocate(7 + size).order(ByteOrder.LITTLE_ENDIAN)
        b.put(2).putShort(1).putShort(message.toShort()).putShort(size.toShort())
        tlvs.forEach { (tag, value) -> b.put(tag.toByte()).putShort(value.size.toShort()).put(value) }
        return b.array().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}

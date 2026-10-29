package dev.ririxi.bunnylock.root

import androidx.annotation.StringRes
import dev.ririxi.bunnylock.core.Bands
import dev.ririxi.bunnylock.core.toIntList
import dev.ririxi.bunnylock.modem.R
import dev.ririxi.bunnylock.modem.Text
import dev.ririxi.bunnylock.modem.toJson
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * Band and cell locks apply live, without a modem restart.
 *
 * NAS message, TLV and enum ids follow Qualcomm's NAS IDL (network_access_service_v01, version 1.0x190).
 * DMS ids and QMI error codes follow libqmi.
 */
object Qualcomm {
    // QMI services: device management and network access.
    private const val DMS = 0x02
    private const val NAS = 0x03

    // Message ids. SSP is the system selection preference: the modes, bands and network the modem may use.
    private const val NAS_ATTACH_DETACH = 0x0023
    private const val NAS_GET_SERVING_SYSTEM = 0x0024
    private const val NAS_GET_RF_BAND_INFO = 0x0031
    private const val NAS_SET_SSP = 0x0033
    private const val NAS_GET_SSP = 0x0034
    private const val NAS_GET_CELL_LOCATION_INFO = 0x0043
    private const val NAS_BIND_SUBSCRIPTION = 0x0045
    private const val NAS_GET_SYS_INFO = 0x004D
    private const val NAS_GET_LTE_CPHY_CA_INFO = 0x00AC
    private const val NAS_LTE_LOCK_GET = 0x00D7
    private const val NAS_LTE_LOCK_SET = 0x00D8
    private const val NAS_NR_LOCK_SET = 0x010E
    private const val NAS_NR_LOCK_GET = 0x010F
    private const val NAS_LTE_LOCK_GET_FULL_EARFCN = 0x015A
    private const val DMS_SET_OPERATING_MODE = 0x002E
    private const val DMS_GET_BAND_CAPABILITIES = 0x0045

    // A TLV id only means something within its message, so each one is named after the message it belongs to.
    private const val TLV_BIND_SUBSCRIPTION_TYPE = 0x01
    private const val TLV_OPERATING_MODE = 0x01
    private const val TLV_ATTACH_ACTION = 0x10
    private const val TLV_SERVING_SYSTEM = 0x01

    // DMS_GET_BAND_CAPABILITIES
    private const val TLV_CAPS_LEGACY_BANDS = 0x01
    private const val TLV_CAPS_LTE_BANDS = 0x10
    private const val TLV_CAPS_LTE_EXT_BANDS = 0x12
    private const val TLV_CAPS_NR_BANDS = 0x13

    // NAS_SET_SSP and NAS_GET_SSP. Some preferences have a different id in each direction.
    private const val TLV_SSP_MODE_PREFERENCE = 0x11
    private const val TLV_SSP_LEGACY_BANDS = 0x12
    private const val TLV_SSP_LTE_BANDS = 0x15
    private const val TLV_SSP_NETWORK_SELECTION = 0x16
    private const val TLV_SSP_CHANGE_DURATION = 0x17
    private const val TLV_SSP_MNC_PCS_DIGIT = 0x1A
    private const val TLV_SSP_MANUAL_PLMN_GET = 0x1B
    private const val TLV_SSP_LTE_EXT_BANDS_GET = 0x23
    private const val TLV_SSP_LTE_EXT_BANDS_SET = 0x24
    private const val TLV_SSP_NR_BANDS_GET = 0x28
    private const val TLV_SSP_NR_BANDS_SET = 0x2B
    private const val TLV_SSP_NR_MODE_GET = 0x2B
    private const val TLV_SSP_NR_SA_BANDS_GET = 0x2C
    private const val TLV_SSP_NR_NSA_BANDS_GET = 0x2D
    private const val TLV_SSP_NR_MODE_SET = 0x2E
    private const val TLV_SSP_NR_SA_BANDS_SET = 0x2F
    private const val TLV_SSP_NR_NSA_BANDS_SET = 0x30

    // NAS_LTE_LOCK_GET, NAS_LTE_LOCK_GET_FULL_EARFCN and NAS_LTE_LOCK_SET
    private const val TLV_LTE_LOCK_GET_CELLS = 0x10
    private const val TLV_LTE_LOCK_GET_FULL_EARFCN_CELLS = 0x10
    private const val TLV_LTE_LOCK_SET_CELLS = 0x01

    // Asks the modem to apply the lock without a restart.
    private const val TLV_LTE_LOCK_SET_ENFORCE = 0x10

    // The IDL allows 60 cells in one lock. Older modems take 20 and answer more with ERR_ARG_TOO_LONG.
    private const val LTE_LOCK_MAX_CELLS = 60
    private const val LTE_LOCK_MAX_CELLS_OLD_MODEMS = 20
    private const val ERR_ARG_TOO_LONG = 0x13

    // The modem's answer when LTE is active and it only changes the cell lock with LTE off.
    private const val ERR_INCOMPATIBLE_STATE = 0x5A
    private const val LTE_OFF_ATTEMPTS = 4
    private const val LTE_OFF_RETRY_MS = 1000L

    // NAS_NR_LOCK_GET and NAS_NR_LOCK_SET
    private const val TLV_NR_LOCK_GET_TYPE = 0x10
    private const val TLV_NR_LOCK_GET_PCI_CELL = 0x11
    private const val TLV_NR_LOCK_GET_ARFCNS = 0x12
    private const val TLV_NR_LOCK_GET_MULTI_PCI = 0x13
    private const val TLV_NR_LOCK_SET_TYPE = 0x01
    private const val TLV_NR_LOCK_SET_PCI_CELL = 0x10
    private const val TLV_NR_LOCK_SET_ARFCNS = 0x11
    private const val TLV_NR_LOCK_SET_MULTI_PCI = 0x12

    // NAS_GET_SYS_INFO
    private const val TLV_SYS_INFO_ENDC_AVAILABLE = 0x4E
    private const val TLV_SYS_INFO_NR_PCI = 0x54
    private const val TLV_SYS_INFO_NR_SCS = 0x5D
    private const val TLV_SYS_INFO_NR_ARFCN = 0x60

    // NAS_GET_CELL_LOCATION_INFO
    private const val TLV_CELL_LOCATION_LTE_INTRA = 0x13
    private const val TLV_CELL_LOCATION_LTE_INTER = 0x14
    private const val TLV_CELL_LOCATION_LTE_INTRA_EARFCN = 0x27
    private const val TLV_CELL_LOCATION_LTE_INTER_EARFCNS = 0x28
    private const val TLV_CELL_LOCATION_NR_ARFCN = 0x2E
    private const val TLV_CELL_LOCATION_NR_CELL = 0x2F

    // NAS_GET_LTE_CPHY_CA_INFO
    private const val TLV_CA_SCELL = 0x12
    private const val TLV_CA_PCELL = 0x13
    private const val TLV_CA_SCELLS = 0x15
    private const val CA_SCELL_ACTIVATED = 2

    /** In kHz, indexed by QMI's bandwidth enum. */
    private val CA_BANDWIDTHS_KHZ = listOf(1400, 3000, 5000, 10_000, 15_000, 20_000)

    /** The LTE bands QMI numbers from 120 up. */
    private val QMI_LTE_BANDS =
        buildList {
            addAll(1..14)
            add(17)
            addAll(33..40)
            addAll(18..21)
            addAll(listOf(24, 25, 41, 42, 43, 23, 26, 32, 125, 126, 127, 28, 29, 30, 66, 250, 46, 27, 31, 71, 47, 48))
            addAll(listOf(67, 68, 49, 85, 72, 73, 86, 53, 87, 88, 70, 44, 256, 106))
        }
    private const val QMI_FIRST_LTE_BAND = 120

    /** The 5G bands QMI numbers from 250 up. */
    private val QMI_NR_BANDS =
        buildList {
            addAll(
                listOf(
                    1,
                    2,
                    3,
                    5,
                    7,
                    8,
                    20,
                    28,
                    38,
                    41,
                    50,
                    51,
                    66,
                    70,
                    71,
                    74,
                    75,
                    76,
                    77,
                    78,
                    79,
                    80,
                    81,
                    82,
                    83,
                    84,
                    85
                )
            )
            addAll(257..261)
            addAll(listOf(12, 25, 34, 39, 40, 65, 86, 48, 14, 13, 18, 26, 30, 29, 53, 46, 91, 92, 93, 94))
            addAll(listOf(96, 106, 67, 68, 89, 95, 97, 98, 99, 104, 47, 102, 105))
        }
    private const val QMI_FIRST_NR_BAND = 250
    private const val RADIO_INTERFACE_NR = 0x0C

    // NAS_GET_RF_BAND_INFO
    private const val TLV_RF_BAND_INFO_LIST = 0x01
    private const val TLV_RF_BAND_INFO_EXT_LIST = 0x11

    private const val DMS_MODE_OFFLINE = 3
    private const val DMS_MODE_RESET = 4

    private const val NETWORK_SELECTION_AUTOMATIC = 0
    private const val NETWORK_SELECTION_MANUAL = 1

    private const val NR_MODE_SA_AND_NSA = 0L

    private const val NR_LOCK_TYPE_PCI = 0
    private const val NR_LOCK_TYPE_ARFCN = 1
    private const val NR_LOCK_TYPE_NONE = 2
    private const val NR_LOCK_TYPE_MULTI_PCI = 3
    private const val NR_LOCK_MAX_PCIS_PER_FREQUENCY = 8

    private const val PS_ATTACH = 1
    private const val PS_DETACH = 2

    private const val ERR_INTERNAL = 0x03
    private const val ERR_NO_EFFECT = 0x1A

    private const val LTE_MASK_BYTES = 8
    private const val LTE_EXT_MASK_BYTES = 32
    private const val NR_MASK_BYTES = 64

    // The locked cell as NAS_NR_LOCK_GET reports it: PCI (u16), SCS (u32), ARFCN (u32), band mask.
    private const val NR_LOCKED_CELL_BYTES = 2 + 4 + 4 + NR_MASK_BYTES

    /**
     * Bit of each band in the 64-bit "legacy" mask that GSM and WCDMA share.
     * GSM bands go by their MHz name (900 is both E-GSM and P-GSM), WCDMA bands by number.
     * WCDMA bands 10 and 12 to 18 have no bit in this mask, and bits 56 to 59 belong to CDMA band classes.
     */
    private val GSM_BITS = mapOf(850 to listOf(19), 900 to listOf(8, 9), 1800 to listOf(7), 1900 to listOf(21))
    private val WCDMA_BITS =
        mapOf(
            1 to 22,
            2 to 23,
            3 to 24,
            4 to 25,
            5 to 26,
            6 to 27,
            7 to 48,
            8 to 49,
            9 to 50,
            11 to 61,
            19 to 60,
        )
    private val GSM_MASK = bitMask(GSM_BITS.values.flatten())
    private val WCDMA_MASK = bitMask(WCDMA_BITS.values)

    /** Subcarrier spacings in kHz. The modem's enum value for each is its index. */
    private val SCS_KHZ = listOf(15, 30, 60, 120, 240)

    private fun bitMask(bits: Iterable<Int>): Long = bits.fold(0L) { mask, bit -> mask or (1L shl bit) }

    fun available(): Boolean =
        try {
            QmiClient(NAS, timeoutMs = 1500).close()
            true
        } catch (_: Throwable) {
            false
        }

    /**
     * Runs [block] on a NAS client bound to [sim]'s subscription. Binding is per socket, and an unbound
     * client uses the primary subscription, so a rejected bind only matters beyond SIM 1.
     */
    private inline fun <T> nas(
        sim: Int,
        timeoutMs: Int = 3000,
        block: (QmiClient) -> T,
    ): T =
        QmiClient(NAS, timeoutMs).use { client ->
            if (sim >= 1) {
                // Subscription type: 0 primary, 1 secondary.
                val bind = client.send(NAS_BIND_SUBSCRIPTION, Tlvs().u8(TLV_BIND_SUBSCRIPTION_TYPE, sim - 1))
                if (!bind.ok && sim > 1) fail(R.string.qc_sim_switch_failed, sim, QmiResponse.errorName(bind.error))
            }
            block(client)
        }

    /** Starts a NAS_SET_SSP request. Every change says whether it is permanent (1) or only lasts until the modem restarts (0). */
    private fun sspRequest(persist: Boolean) = Tlvs().u8(TLV_SSP_CHANGE_DURATION, if (persist) 1 else 0)

    private fun legacyToGsm(mask: Long) =
        GSM_BITS.filter { (_, bits) -> bits.any { mask and (1L shl it) != 0L } }.keys.sorted()

    private fun legacyToWcdma(mask: Long) = WCDMA_BITS.filter { (_, bit) -> mask and (1L shl bit) != 0L }.keys.sorted()

    private fun QmiResponse.legacyBandMask(tlvId: Int): Long? =
        tlv(tlvId)?.takeIf { it.size >= 8 }?.let { Reader(it).u64() }

    private fun u16List(value: ByteArray): List<Int> {
        val reader = Reader(value)
        return List(reader.u16()) { reader.u16() }
    }

    private fun scsEnum(khz: Int): Int {
        val index = SCS_KHZ.indexOf(khz)
        if (index < 0) fail(R.string.qc_invalid_scs, khz)
        return index
    }

    private fun scsKhz(enumValue: Int): Int = SCS_KHZ.getOrElse(enumValue) { 0 }

    private class HardwareBands(
        val legacyMask: Long,
        val lte: List<Int>,
        val nr: List<Int>,
    )

    private fun hardwareBands(): HardwareBands =
        QmiClient(DMS).use { client ->
            val response = client.send(DMS_GET_BAND_CAPABILITIES).check(R.string.qc_op_band_caps_query)
            val legacyMask = response.legacyBandMask(TLV_CAPS_LEGACY_BANDS) ?: 0L
            // The LTE mask stops at band 64. The extended TLV is a list of band numbers instead.
            val lte = sortedSetOf<Int>()
            response.tlv(TLV_CAPS_LTE_BANDS)?.takeIf { it.size >= LTE_MASK_BYTES }
                ?.let { lte += Bands.fromMask(it.copyOf(LTE_MASK_BYTES)) }
            response.tlv(TLV_CAPS_LTE_EXT_BANDS)?.let { lte += u16List(it) }
            val nr = sortedSetOf<Int>()
            response.tlv(TLV_CAPS_NR_BANDS)?.let { nr += u16List(it) }
            HardwareBands(legacyMask, lte.toList(), nr.toList())
        }

    fun state(sim: Int): JSONObject {
        val out = JSONObject()
        val hardware = runCatching { hardwareBands() }.getOrNull()
        if (hardware !== null) {
            out.put(
                "hw",
                JSONObject()
                    .put("gsm", JSONArray(legacyToGsm(hardware.legacyMask)))
                    .put("wcdma", JSONArray(legacyToWcdma(hardware.legacyMask)))
                    .put("lte", JSONArray(hardware.lte))
                    .put("nr", JSONArray(hardware.nr)),
            )
        }
        nas(sim) { client ->
            putSystemSelection(client.send(NAS_GET_SSP).check(R.string.qc_op_ssp_query), out)
            runCatching { out.put("lteLock", lteLockState(client)) }
            runCatching { out.put("nrLock", nrLockState(client)) }
        }
        return out
    }

    private fun putSystemSelection(
        response: QmiResponse,
        out: JSONObject,
    ) {
        response.tlv(TLV_SSP_MODE_PREFERENCE)?.takeIf { it.size >= 2 }?.let { out.put("rat", Reader(it).u16()) }
        response.legacyBandMask(TLV_SSP_LEGACY_BANDS)?.let { mask ->
            out.put("gsm", JSONArray(legacyToGsm(mask))).put("wcdma", JSONArray(legacyToWcdma(mask)))
        }
        val lteMask =
            response.tlv(TLV_SSP_LTE_EXT_BANDS_GET)?.takeIf { it.size >= LTE_EXT_MASK_BYTES }
                ?: response.tlv(TLV_SSP_LTE_BANDS)?.takeIf { it.size >= LTE_MASK_BYTES }
        lteMask?.let { out.put("lte", JSONArray(Bands.fromMask(it))) }
        response.tlv(TLV_SSP_NR_SA_BANDS_GET)?.takeIf { it.size >= NR_MASK_BYTES }
            ?.let { out.put("nrSa", JSONArray(Bands.fromMask(it))) }
        response.tlv(TLV_SSP_NR_NSA_BANDS_GET)?.takeIf { it.size >= NR_MASK_BYTES }
            ?.let { out.put("nrNsa", JSONArray(Bands.fromMask(it))) }
        response.tlv(TLV_SSP_NR_BANDS_GET)?.takeIf { it.size >= NR_MASK_BYTES }
            ?.let { out.put("nr", JSONArray(Bands.fromMask(it))) }
        // 0 allows SA and NSA, 1 disables SA, 2 disables NSA.
        response.tlv(TLV_SSP_NR_MODE_GET)?.takeIf { it.size == 4 }?.let { out.put("nrMode", Reader(it).i32()) }
        // On GET the IDL puts only the mode (0 automatic, 1 manual) here and the PLMN in TLV_SSP_MANUAL_PLMN_GET.
        // A longer value is read as mode, MCC and MNC, the layout SET uses.
        response.tlv(TLV_SSP_NETWORK_SELECTION)?.takeIf { it.isNotEmpty() }?.let {
            val reader = Reader(it)
            val selection = JSONObject().put("mode", reader.u8())
            if (it.size >= 5) selection.put("mcc", reader.u16()).put("mnc", reader.u16())
            out.put("netSel", selection)
        }
        // MCC, MNC, whether the MNC has 3 digits.
        response.tlv(TLV_SSP_MANUAL_PLMN_GET)?.takeIf { it.size >= 5 }?.let {
            val reader = Reader(it)
            out.put(
                "manualPlmn",
                JSONObject().put("mcc", reader.u16()).put("mnc", reader.u16()).put("pcs", reader.u8() != 0)
            )
        }
    }

    /** Only newer modems know NAS_LTE_LOCK_GET_FULL_EARFCN. NAS_LTE_LOCK_GET reads EARFCNs back as 16 bits even though SET takes 32. */
    private fun lteLockState(client: QmiClient): JSONObject {
        val fullEarfcnResponse = runCatching { client.send(NAS_LTE_LOCK_GET_FULL_EARFCN) }.getOrNull()?.takeIf { it.ok }
        val cells =
            if (fullEarfcnResponse !== null) {
                lteLockCells(fullEarfcnResponse.tlv(TLV_LTE_LOCK_GET_FULL_EARFCN_CELLS), fullEarfcn = true)
            } else {
                val response = client.send(NAS_LTE_LOCK_GET).check(R.string.qc_op_lte_cell_lock_query)
                lteLockCells(response.tlv(TLV_LTE_LOCK_GET_CELLS), fullEarfcn = false)
            }
        return JSONObject()
            .put("active", cells.length() > 0)
            .put("cells", cells)
            .put("earfcnTruncated", fullEarfcnResponse === null)
    }

    private fun lteLockCells(
        value: ByteArray?,
        fullEarfcn: Boolean,
    ): JSONArray {
        val cells = JSONArray()
        if (value === null) return cells
        val reader = Reader(value)
        val bytesPerCell = if (fullEarfcn) 6 else 4
        repeat(reader.u8()) {
            if (reader.remaining >= bytesPerCell) {
                val pci = reader.u16()
                val earfcn = if (fullEarfcn) reader.u32() else reader.u16().toLong()
                cells.put(JSONObject().put("pci", pci).put("earfcn", earfcn))
            }
        }
        return cells
    }

    private fun nrLockState(client: QmiClient): JSONObject {
        val response = client.send(NAS_NR_LOCK_GET).check(R.string.qc_op_nr_cell_lock_query)
        val type =
            response.tlv(TLV_NR_LOCK_GET_TYPE)?.takeIf { it.size >= 4 }?.let { Reader(it).i32() } ?: NR_LOCK_TYPE_NONE
        val lock = JSONObject().put("type", type).put("active", type != NR_LOCK_TYPE_NONE)
        response.tlv(TLV_NR_LOCK_GET_PCI_CELL)?.takeIf { it.size >= NR_LOCKED_CELL_BYTES }?.let {
            val reader = Reader(it)
            val pci = reader.u16()
            val scs = reader.i32()
            val arfcn = reader.u32()
            val bands = Bands.fromMask(reader.bytes(NR_MASK_BYTES))
            lock
                .put("pci", pci)
                .put("scs", scsKhz(scs))
                .put("arfcn", arfcn)
                .put("band", bands.firstOrNull() ?: 0)
        }
        response.tlv(TLV_NR_LOCK_GET_ARFCNS)?.let {
            val reader = Reader(it)
            val arfcns = JSONArray()
            repeat(reader.u8()) {
                if (reader.remaining >= 8) arfcns.put(
                    JSONObject().put("arfcn", reader.u32()).put("scs", scsKhz(reader.i32()))
                )
            }
            lock.put("arfcns", arfcns)
        }
        // Each frequency holds a PCI count, the PCIs (u16 each), an SCS mask (u16), the ARFCN (u32) and a band mask.
        // Only the first frequency is read, since we set one
        response.tlv(TLV_NR_LOCK_GET_MULTI_PCI)?.takeIf { it.size >= 2 }?.let {
            val reader = Reader(it)
            val frequencyCount = reader.u8()
            val pciCount = reader.u8()
            if (frequencyCount > 0 && reader.remaining >= pciCount * 2 + 2 + 4) {
                val pcis = List(pciCount) { reader.u16() }
                reader.skip(2)
                lock.put("arfcn", reader.u32()).put("pcis", JSONArray(pcis))
            }
        }
        return lock
    }

    /** kind: gsm | wcdma | lte | nr (both SA+NSA) | nrSa | nrNsa. Empty list = hardware default. */
    fun setBands(
        sim: Int,
        kind: String,
        requested: List<Int>,
        persist: Boolean,
    ): JSONObject {
        val hardware = hardwareBands()
        val bands =
            requested.ifEmpty {
                when (kind) {
                    "gsm" -> legacyToGsm(hardware.legacyMask)
                    "wcdma" -> legacyToWcdma(hardware.legacyMask)
                    "lte" -> hardware.lte
                    else -> hardware.nr
                }
            }
        val notes = JSONArray()
        nas(sim) { client ->
            when (kind) {
                "lte" -> {
                    client.send(NAS_SET_SSP, lteBandsRequest(bands, persist)).check(R.string.qc_op_lte_band_lock)
                }

                "gsm", "wcdma" -> {
                    setLegacyBands(client, kind, bands, hardware.legacyMask, persist)
                }

                "nrSa", "nrNsa" -> {
                    setNrSaOrNsaBands(client, kind, bands, persist, notes)
                }

                "nr" -> {
                    // One accepted mask is enough. When all three are refused, the first refusal is reported.
                    val results = setAllNrMasks(client, Bands.toMask(bands, NR_MASK_BYTES), persist)
                    if (results.none { it.ok }) results.first().check(R.string.qc_op_nr_band_lock)
                }

                else -> fail(R.string.qc_unknown_band_group, kind)
            }
        }
        return JSONObject().put("applied", JSONArray(bands)).put("notes", notes)
    }

    /** GSM and WCDMA share one mask, so the other technology's bits are carried over from the current setting. */
    private fun setLegacyBands(
        client: QmiClient,
        kind: String,
        bands: List<Int>,
        hardwareMask: Long,
        persist: Boolean,
    ) {
        val currentMask =
            client
                .send(NAS_GET_SSP)
                .check(R.string.qc_op_legacy_band_query)
                .legacyBandMask(TLV_SSP_LEGACY_BANDS) ?: hardwareMask
        val isGsm = kind == "gsm"
        val technologyMask = if (isGsm) GSM_MASK else WCDMA_MASK
        val selectedBits = if (isGsm) bands.flatMap { GSM_BITS[it].orEmpty() } else bands.mapNotNull { WCDMA_BITS[it] }
        val newMask = (currentMask and technologyMask.inv()) or bitMask(selectedBits)
        client
            .send(NAS_SET_SSP, sspRequest(persist).add(TLV_SSP_LEGACY_BANDS, LittleEndian.u64(newMask)))
            .check(if (isGsm) R.string.qc_op_gsm_band_lock else R.string.qc_op_wcdma_band_lock)
    }

    private fun setNrSaOrNsaBands(
        client: QmiClient,
        kind: String,
        bands: List<Int>,
        persist: Boolean,
        notes: JSONArray,
    ) {
        val isSa = kind == "nrSa"
        val mask = Bands.toMask(bands, NR_MASK_BYTES)
        val bandsTlv = if (isSa) TLV_SSP_NR_SA_BANDS_SET else TLV_SSP_NR_NSA_BANDS_SET
        if (client.send(NAS_SET_SSP, sspRequest(persist).add(bandsTlv, mask)).ok) return
        // Older firmware only knows the combined NR mask.
        client.send(NAS_SET_SSP, sspRequest(persist).add(TLV_SSP_NR_BANDS_SET, mask)).check(R.string.qc_op_nr_band_lock)
        val modeName = if (isSa) "SA" else "NSA"
        notes.put(Text(R.string.qc_note_nr_bands_combined, modeName).toJson())
    }

    /** Only one of the two LTE masks goes into a request, because some firmware rejects them together. */
    private fun lteBandsRequest(
        bands: List<Int>,
        persist: Boolean,
    ): Tlvs {
        val request = sspRequest(persist)
        val needsExtendedMask = bands.any { it > 64 }
        if (needsExtendedMask) return request.add(TLV_SSP_LTE_EXT_BANDS_SET, Bands.toMask(bands, LTE_EXT_MASK_BYTES))
        return request.add(TLV_SSP_LTE_BANDS, Bands.toMask(bands, LTE_MASK_BYTES))
    }

    /** Sets the combined, SA and NSA masks in turn. Not every firmware knows all three, so the caller decides what a refusal means. */
    private fun setAllNrMasks(
        client: QmiClient,
        mask: ByteArray,
        persist: Boolean,
    ): List<QmiResponse> =
        listOf(TLV_SSP_NR_BANDS_SET, TLV_SSP_NR_SA_BANDS_SET, TLV_SSP_NR_NSA_BANDS_SET)
            .map { client.send(NAS_SET_SSP, sspRequest(persist).add(it, mask)) }

    fun reset(
        sim: Int,
        persist: Boolean,
    ): JSONObject {
        val hardware = hardwareBands()
        nas(sim) { client ->
            client.send(
                NAS_SET_SSP,
                sspRequest(persist).add(TLV_SSP_LEGACY_BANDS, LittleEndian.u64(hardware.legacyMask))
            )
            client.send(NAS_SET_SSP, lteBandsRequest(hardware.lte, persist)).check(R.string.qc_op_lte_band_restore)
            if (hardware.nr.isNotEmpty()) {
                setAllNrMasks(client, Bands.toMask(hardware.nr, NR_MASK_BYTES), persist)
                client.send(NAS_SET_SSP, sspRequest(persist).add(TLV_SSP_NR_MODE_SET, LittleEndian.u32(NR_MODE_SA_AND_NSA)))
            }
        }
        return JSONObject().put("restored", true)
    }

    /** QMI mode preference bits: GSM 0x04, UMTS 0x08, LTE 0x10, NR5G 0x40. */
    fun setRat(
        sim: Int,
        mask: Int,
        persist: Boolean,
    ): JSONObject {
        nas(sim) { client ->
            client
                .send(NAS_SET_SSP, sspRequest(persist).add(TLV_SSP_MODE_PREFERENCE, LittleEndian.u16(mask)))
                .check(R.string.qc_op_network_mode)
        }
        return JSONObject().put("rat", mask)
    }

    /** 0 = SA + NSA, 1 = NSA only, 2 = SA only. */
    fun setNrMode(
        sim: Int,
        mode: Int,
        persist: Boolean,
    ): JSONObject {
        nas(sim) { client ->
            client.send(NAS_SET_SSP, sspRequest(persist).add(TLV_SSP_NR_MODE_SET, LittleEndian.u32(mode.toLong())))
                .check(R.string.qc_op_nr_mode)
        }
        return JSONObject().put("nrMode", mode)
    }

    private fun networkSelection(
        mode: Int,
        mcc: Int,
        mnc: Int,
    ): ByteArray = byteArrayOf(mode.toByte()) + LittleEndian.u16(mcc) + LittleEndian.u16(mnc)

    /** [threeDigitMnc] distinguishes e.g. "090" from "90". */
    fun setPlmn(
        sim: Int,
        mcc: Int,
        mnc: Int,
        threeDigitMnc: Boolean,
        persist: Boolean,
    ): JSONObject =
        nas(sim) { client ->
            val selection = networkSelection(NETWORK_SELECTION_MANUAL, mcc, mnc)
            val withPcsDigit =
                sspRequest(persist)
                    .add(TLV_SSP_NETWORK_SELECTION, selection)
                    .u8(TLV_SSP_MNC_PCS_DIGIT, if (threeDigitMnc) 1 else 0)
            var response = client.send(NAS_SET_SSP, withPcsDigit)
            // Some firmware rejects the PCS-digit TLV. The selection alone still works for most MNCs.
            if (!response.ok) response =
                client.send(NAS_SET_SSP, sspRequest(persist).add(TLV_SSP_NETWORK_SELECTION, selection))
            response.check(R.string.qc_op_plmn_lock)
            JSONObject().put("mcc", mcc).put("mnc", mnc)
        }

    fun clearPlmn(
        sim: Int,
        persist: Boolean,
    ): JSONObject =
        nas(sim) { client ->
            val selection = networkSelection(NETWORK_SELECTION_AUTOMATIC, mcc = 0, mnc = 0)
            client.send(NAS_SET_SSP, sspRequest(persist).add(TLV_SSP_NETWORK_SELECTION, selection))
                .check(R.string.qc_op_plmn_unlock)
            JSONObject().put("automatic", true)
        }

    /** LTE has no EARFCN-only mode, so pass every PCI you accept. */
    fun lteLock(
        sim: Int,
        earfcn: Long,
        pcis: List<Int>,
    ): JSONObject {
        if (pcis.isEmpty() || pcis.size > LTE_LOCK_MAX_CELLS) fail(R.string.qc_pci_count, LTE_LOCK_MAX_CELLS)
        val cells = ByteArrayOutputStream()
        cells.write(pcis.size)
        for (pci in pcis) {
            cells.write(LittleEndian.u16(pci))
            cells.write(LittleEndian.u32(earfcn))
        }
        return nas(sim) { client -> setLteCells(client, cells.toByteArray(), R.string.qc_op_lte_cell_lock) }
    }

    // An empty cell list (a count of zero) clears the lock.
    fun lteUnlock(sim: Int): JSONObject =
        nas(sim) { client -> setLteCells(client, byteArrayOf(0), R.string.qc_op_lte_cell_unlock) }

    /**
     * Some modems only change the LTE cell lock while LTE is off, so a refusal for that reason is sent again in
     * airplane mode. The result says whether that was needed.
     */
    private fun setLteCells(
        client: QmiClient,
        cells: ByteArray,
        @StringRes what: Int,
    ): JSONObject {
        fun send() =
            client.send(NAS_LTE_LOCK_SET, Tlvs().add(TLV_LTE_LOCK_SET_CELLS, cells).u8(TLV_LTE_LOCK_SET_ENFORCE, 1))

        val response = send()
        val needsLteOff = response.error == ERR_INCOMPATIBLE_STATE
        if (needsLteOff) {
            Aosp.inAirplaneMode {
                // The radio takes a moment to go quiet after airplane mode comes on.
                var retry = send()
                for (attempt in 2..LTE_OFF_ATTEMPTS) {
                    if (retry.error != ERR_INCOMPATIBLE_STATE) break
                    Thread.sleep(LTE_OFF_RETRY_MS)
                    retry = send()
                }
                retry.checkLteLock(what)
            }
        } else {
            response.checkLteLock(what)
        }
        val state = runCatching { lteLockState(client) }.getOrDefault(JSONObject())
        return state.put("airplaneMode", needsLteOff)
    }

    private fun QmiResponse.checkLteLock(@StringRes what: Int): QmiResponse {
        if (error == ERR_ARG_TOO_LONG) fail(R.string.qc_lte_lock_too_many_pcis, Text(what), LTE_LOCK_MAX_CELLS_OLD_MODEMS)
        return check(what)
    }

    /**
     * type: "pci", "arfcn" (frequency only) or "multi" (several PCIs on one ARFCN).
     * Applies to the SCG in NSA and the PCell in SA.
     */
    fun nrLock(
        sim: Int,
        args: JSONObject,
    ): JSONObject {
        val request = nrLockRequestFor(args)
        return nas(sim) { client ->
            client.send(NAS_NR_LOCK_SET, request).check(R.string.qc_op_nr_cell_lock)
            nrLockState(client)
        }
    }

    private fun nrLockRequest(type: Int) = Tlvs().u32(TLV_NR_LOCK_SET_TYPE, type.toLong())

    private fun nrLockRequestFor(args: JSONObject): Tlvs {
        val type = args.getString("type")
        val arfcn = args.optLong("arfcn")
        val scsKhz = args.optInt("scs", 30)
        val band = args.optInt("band", Bands.nrBandsOf(arfcn.toInt()).firstOrNull() ?: 0)
        return when (type) {
            "pci" -> {
                if (band <= 0) fail(R.string.qc_needs_nr_band)
                val cell = ByteArrayOutputStream()
                cell.write(LittleEndian.u16(args.getInt("pci")))
                cell.write(LittleEndian.u32(scsEnum(scsKhz).toLong()))
                cell.write(LittleEndian.u32(arfcn))
                cell.write(Bands.toMask(listOf(band), NR_MASK_BYTES))
                nrLockRequest(NR_LOCK_TYPE_PCI).add(TLV_NR_LOCK_SET_PCI_CELL, cell.toByteArray())
            }

            "arfcn" -> {
                val arfcns = ByteArrayOutputStream()
                // A list with a single entry.
                arfcns.write(1)
                arfcns.write(LittleEndian.u32(arfcn))
                arfcns.write(LittleEndian.u32(scsEnum(scsKhz).toLong()))
                nrLockRequest(NR_LOCK_TYPE_ARFCN).add(TLV_NR_LOCK_SET_ARFCNS, arfcns.toByteArray())
            }

            "multi" -> {
                if (band <= 0) fail(R.string.qc_needs_nr_band)
                val pcis = args.getJSONArray("pcis").toIntList()
                if (pcis.isEmpty() || pcis.size > NR_LOCK_MAX_PCIS_PER_FREQUENCY) fail(R.string.qc_pci_count, NR_LOCK_MAX_PCIS_PER_FREQUENCY)
                val cells = ByteArrayOutputStream()
                // A list with a single frequency.
                cells.write(1)
                cells.write(pcis.size)
                pcis.forEach { cells.write(LittleEndian.u16(it)) }
                // Unlike the single-PCI lock, the SCS goes in as a bit mask of enum values.
                cells.write(LittleEndian.u16(1 shl scsEnum(scsKhz)))
                cells.write(LittleEndian.u32(arfcn))
                cells.write(Bands.toMask(listOf(band), NR_MASK_BYTES))
                nrLockRequest(NR_LOCK_TYPE_MULTI_PCI).add(TLV_NR_LOCK_SET_MULTI_PCI, cells.toByteArray())
            }

            else -> {
                fail(R.string.qc_unknown_nr_lock_type, type)
            }
        }
    }

    fun nrUnlock(sim: Int): JSONObject =
        nas(sim) { client ->
            // Firmware rejects an idle clear and may reject the immediate readback after a successful clear,
            // so the lock is read first and the cleared state is reported without asking again.
            val lock = nrLockState(client)
            if (lock.optBoolean("active")) {
                client.send(NAS_NR_LOCK_SET, nrLockRequest(NR_LOCK_TYPE_NONE)).check(R.string.qc_op_nr_cell_unlock)
                JSONObject().put("type", NR_LOCK_TYPE_NONE).put("active", false)
            } else {
                lock
            }
        }

    fun reboot(): JSONObject =
        QmiClient(DMS, 20_000).use { client ->
            client.send(DMS_SET_OPERATING_MODE, Tlvs().u8(TLV_OPERATING_MODE, DMS_MODE_OFFLINE))
                .check(R.string.qc_op_offline)
            client.send(DMS_SET_OPERATING_MODE, Tlvs().u8(TLV_OPERATING_MODE, DMS_MODE_RESET))
                .check(R.string.qc_op_reboot)
            JSONObject().put("method", "qmi")
        }

    fun serving(sim: Int): JSONObject =
        nas(sim) { client ->
            val out = JSONObject()
            runCatching { putSysInfo(client, out) }
            runCatching { putCellLocation(client, out) }
            runCatching { putRfBandInfo(client, out) }
            runCatching { putCarrierAggregation(client, out) }
            putNrFromRfBands(out)
            out.optJSONObject("nr")?.let { nr ->
                val arfcn = nr.optLong("arfcn", -1)
                if (arfcn > 0) nr.put("bands", JSONArray(Bands.nrBandsOf(arfcn.toInt())))
            }
            out.optJSONObject("lte")?.let { lte ->
                Bands.lteBandOf(lte.optLong("earfcn").toInt())?.let { lte.put("band", it) }
            }
            out
        }

    private fun JSONObject.childObject(key: String): JSONObject =
        optJSONObject(key) ?: JSONObject().also { put(key, it) }

    private fun putSysInfo(
        client: QmiClient,
        out: JSONObject,
    ) {
        val response = client.send(NAS_GET_SYS_INFO)
        if (!response.ok) return
        val nr = JSONObject()
        response.tlv(TLV_SYS_INFO_NR_PCI)?.takeIf { it.size >= 2 }?.let { nr.put("pci", Reader(it).u16()) }
        response.tlv(TLV_SYS_INFO_NR_ARFCN)?.takeIf { it.size >= 4 }?.let { nr.put("arfcn", Reader(it).u32()) }
        response.tlv(TLV_SYS_INFO_NR_SCS)?.takeIf { it.size >= 4 }?.let { nr.put("scs", scsKhz(Reader(it).i32())) }
        response.tlv(TLV_SYS_INFO_ENDC_AVAILABLE)?.takeIf { it.isNotEmpty() }
            ?.let { nr.put("endcAvailable", it[0].toInt() != 0) }
        if (nr.has("arfcn") || nr.has("pci")) out.put("nr", nr)
    }

    private fun putCellLocation(
        client: QmiClient,
        out: JSONObject,
    ) {
        val response = client.send(NAS_GET_CELL_LOCATION_INFO)
        if (!response.ok) return
        response.tlv(TLV_CELL_LOCATION_LTE_INTRA)
            ?.let { putLteIntraFrequency(it, response.tlv(TLV_CELL_LOCATION_LTE_INTRA_EARFCN), out) }
        response.tlv(TLV_CELL_LOCATION_LTE_INTER)
            ?.let { putLteInterFrequency(it, response.tlv(TLV_CELL_LOCATION_LTE_INTER_EARFCNS), out) }
        response.tlv(TLV_CELL_LOCATION_NR_CELL)?.let { putNrCell(it, out) }
        response.tlv(TLV_CELL_LOCATION_NR_ARFCN)?.takeIf { it.size >= 4 }
            ?.let { out.childObject("nr").put("arfcn", Reader(it).u32()) }
    }

    private class LteMeasurement(
        val pci: Int,
        val rsrq: Double,
        val rsrp: Double,
    )

    /** RSRQ and RSRP arrive in tenths of a dB. */
    private fun readLteMeasurement(reader: Reader): LteMeasurement {
        val pci = reader.u16()
        val rsrq = reader.i16() / 10.0
        val rsrp = reader.i16() / 10.0
        // RSSI and the cell selection RX level.
        reader.skip(4)
        return LteMeasurement(pci, rsrq, rsrp)
    }

    private fun lteNeighbor(
        measurement: LteMeasurement,
        earfcn: Long,
    ): JSONObject =
        JSONObject()
            .put("pci", measurement.pci)
            .put("earfcn", earfcn)
            .put("rsrp", measurement.rsrp)
            .put("rsrq", measurement.rsrq)

    /** The cell's own EARFCN field is 16 bits wide. When the modem also sends the 32-bit [extendedEarfcn], that one is used. */
    private fun putLteIntraFrequency(
        value: ByteArray,
        extendedEarfcn: ByteArray?,
        out: JSONObject,
    ) {
        val reader = Reader(value)
        // UE-in-idle flag and PLMN.
        reader.skip(4)
        val tac = reader.u16()
        val eci = reader.u32()
        val legacyEarfcn = reader.u16().toLong()
        val pci = reader.u16()
        // Reselection priority and three thresholds.
        reader.skip(4)
        val cellCount = reader.u8()
        val earfcn = extendedEarfcn?.takeIf { it.size >= 4 }?.let { Reader(it).u32() } ?: legacyEarfcn
        val neighbors = JSONArray()
        var servingRsrp: Double? = null
        repeat(cellCount) {
            val measurement = readLteMeasurement(reader)
            if (measurement.pci == pci) {
                servingRsrp = measurement.rsrp
            } else {
                neighbors.put(lteNeighbor(measurement, earfcn))
            }
        }
        val lte =
            JSONObject()
                .put("earfcn", earfcn)
                .put("pci", pci)
                .put("tac", tac)
                .put("eci", eci)
        servingRsrp?.let { lte.put("rsrp", it) }
        out.put("lte", lte)
        out.put("lteNeighbors", neighbors)
    }

    /** [extendedEarfcns] holds a count and then one 32-bit EARFCN per frequency, in the same order as [value]. */
    private fun putLteInterFrequency(
        value: ByteArray,
        extendedEarfcns: ByteArray?,
        out: JSONObject,
    ) {
        val reader = Reader(value)
        // UE-in-idle flag.
        reader.skip(1)
        val frequencyCount = reader.u8()
        val extendedReader = extendedEarfcns?.let { Reader(it) }
        extendedReader?.skip(1)
        val neighbors = out.optJSONArray("lteNeighbors") ?: JSONArray().also { out.put("lteNeighbors", it) }
        repeat(frequencyCount) {
            val legacyEarfcn = reader.u16().toLong()
            // Two RX level thresholds and the reselection priority.
            reader.skip(3)
            val cellCount = reader.u8()
            val earfcn = extendedReader?.u32() ?: legacyEarfcn
            repeat(cellCount) { neighbors.put(lteNeighbor(readLteMeasurement(reader), earfcn)) }
        }
    }

    private fun putNrCell(
        value: ByteArray,
        out: JSONObject,
    ) {
        val reader = Reader(value)
        // PLMN and tracking area code, three bytes each.
        reader.skip(6)
        val nci = reader.u64()
        val pci = reader.u16()
        val rsrq = reader.i16() / 10.0
        val rsrp = reader.i16() / 10.0
        val snr = reader.i16() / 10.0
        out
            .childObject("nr")
            .put("pci", pci)
            .put("nci", nci)
            .put("rsrp", rsrp)
            .put("rsrq", rsrq)
            .put("snr", snr)
    }

    private fun putRfBandInfo(
        client: QmiClient,
        out: JSONObject,
    ) {
        val response = client.send(NAS_GET_RF_BAND_INFO)
        if (!response.ok) return
        val bands = JSONArray()
        // The extended list has 32-bit channel numbers, the basic one 16-bit.
        val extended = response.tlv(TLV_RF_BAND_INFO_EXT_LIST)
        (extended ?: response.tlv(TLV_RF_BAND_INFO_LIST))?.let { value ->
            val reader = Reader(value)
            repeat(reader.u8()) {
                val radioInterface = reader.u8()
                val band = reader.u16()
                val channel = if (extended !== null) reader.u32() else reader.u16().toLong()
                bands.put(JSONObject().put("iface", radioInterface).put("band", band).put("channel", channel))
            }
        }
        out.put("rf", bands)
    }

    /** Some modems leave the 5G channel out of the cell reports, but the RF band report still names it. */
    private fun putNrFromRfBands(out: JSONObject) {
        val rf = out.optJSONArray("rf") ?: return
        val active =
            (0 until rf.length())
                .map { rf.getJSONObject(it) }
                .firstOrNull { it.optInt("iface") == RADIO_INTERFACE_NR && it.optLong("channel") > 0 } ?: return
        val nr = out.childObject("nr")
        if (!nr.has("arfcn")) nr.put("arfcn", active.optLong("channel"))
        QMI_NR_BANDS.getOrNull(active.optInt("band") - QMI_FIRST_NR_BAND)?.let { nr.put("band", it) }
    }

    /** The field is 16 bits, so EARFCNs from 65536 up (bands 66 and 71) arrive cut short. */
    private fun caEarfcn(
        channel: Int,
        band: Int?,
    ): Long {
        if (band === null) return channel.toLong()
        return listOf(channel.toLong(), channel + 0x10000L).firstOrNull { Bands.lteBandOf(it.toInt()) == band }
            ?: channel.toLong()
    }

    private fun caCarrier(
        reader: Reader,
        primary: Boolean,
    ): JSONObject {
        val pci = reader.u16()
        val channel = reader.u16()
        val bandwidth = CA_BANDWIDTHS_KHZ.getOrNull(reader.i32())
        val band = QMI_LTE_BANDS.getOrNull(reader.u16() - QMI_FIRST_LTE_BAND)
        val carrier =
            JSONObject()
                .put("rat", "LTE")
                .put("primary", primary)
                .put("pci", pci)
                .put("channel", caEarfcn(channel, band))
        band?.let { carrier.put("band", it) }
        bandwidth?.let { carrier.put("bandwidth", it) }
        return carrier
    }

    /** QMI leaves 5G NSA carriers out of this report. */
    private fun putCarrierAggregation(
        client: QmiClient,
        out: JSONObject,
    ) {
        val response = client.send(NAS_GET_LTE_CPHY_CA_INFO)
        if (!response.ok) return
        val pcell = response.tlv(TLV_CA_PCELL) ?: return
        val carriers = JSONArray().put(caCarrier(Reader(pcell), primary = true))
        val scellList = response.tlv(TLV_CA_SCELLS)
        if (scellList !== null) {
            val reader = Reader(scellList)
            repeat(reader.u8()) {
                val scell = caCarrier(reader, primary = false)
                val state = reader.i32()
                // The cell index.
                reader.skip(1)
                if (state == CA_SCELL_ACTIVATED) carriers.put(scell)
            }
        } else {
            response.tlv(TLV_CA_SCELL)?.let { value ->
                val reader = Reader(value)
                val scell = caCarrier(reader, primary = false)
                if (reader.i32() == CA_SCELL_ACTIVATED) carriers.put(scell)
            }
        }
        out.put("carriers", carriers)
    }

    /**
     * Detaches from the packet network and attaches again. The response may only arrive once the
     * procedure finishes, hence the long timeout. NO_EFFECT means already in that state.
     */
    fun reattach(sim: Int): JSONObject =
        nas(sim, timeoutMs = 20_000) { client ->
            psAttach(client, PS_DETACH, R.string.qc_op_data_detach)
            Thread.sleep(2000)
            psAttach(client, PS_ATTACH, R.string.qc_op_data_attach)
            JSONObject().put("method", "qmi")
        }

    private fun psAttach(
        client: QmiClient,
        action: Int,
        @StringRes what: Int,
    ) {
        val response = client.send(NAS_ATTACH_DETACH, Tlvs().u8(TLV_ATTACH_ACTION, action))
        if (response.ok || response.error == ERR_NO_EFFECT) return
        // An internal error doesn't always mean the procedure failed, so the attach state gets the last word.
        if (response.error == ERR_INTERNAL && psStateReached(client, action)) return
        fail(R.string.qmi_refused, Text(what), QmiResponse.errorName(response.error))
    }

    private fun psStateReached(
        client: QmiClient,
        action: Int,
    ): Boolean {
        repeat(3) {
            Thread.sleep(500)
            // The attach states are numbered like the actions that lead to them: 1 attached, 2 detached.
            if (psAttachState(client) == action) return true
        }
        return false
    }

    private fun psAttachState(client: QmiClient): Int? =
        runCatching {
            val response = client.send(NAS_GET_SERVING_SYSTEM, timeoutMs = 1000)
            if (!response.ok) return@runCatching null
            // Registration state, CS attach state, PS attach state: one byte each.
            val servingSystem = response.tlv(TLV_SERVING_SYSTEM)?.takeIf { it.size >= 3 } ?: return@runCatching null
            servingSystem[2].toInt() and 0xFF
        }.getOrNull()

    fun raw(
        sim: Int,
        service: Int,
        msg: Int,
        payload: ByteArray,
    ): JSONObject {
        val response =
            if (service == NAS) nas(sim) { it.send(msg, payload) } else QmiClient(service).use { it.send(msg, payload) }
        val tlvs = JSONObject()
        response.tlvs.forEach { (id, value) -> tlvs.put("0x%02X".format(id), value.hex()) }
        return JSONObject()
            .put("result", response.result)
            .put("error", QmiResponse.errorName(response.error).toJson())
            .put("raw", response.raw.hex())
            .put("tlvs", tlvs)
    }
}

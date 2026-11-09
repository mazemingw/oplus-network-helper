package dev.qcom.efs.features

/**
 * One write into an EFS item file. [bytes] is the exact payload as decimal
 * byte values (port of mtbtool's NvWrite, which stores them as a decimal
 * string).
 *
 * [merge] turns the write into a read-modify-write for items that carry a
 * whole capability bitmap rather than a single switch: it receives what the
 * item currently holds (null when absent) and returns the payload to store.
 * Fixing the payload instead would re-enable every bit the item happens to
 * clear. [bytes] stays the fallback the caller can reason about without
 * reading, and is what a newly created item gets.
 */
data class NvWrite(
    val path: String,
    val bytes: List<Int>,
    val merge: ((List<Int>?) -> List<Int>)? = null,
)

/**
 * A modem capability that can be turned off by writing fixed bytes into EFS
 * item files. [reads] lists the item files whose contents determine whether
 * the feature is already disabled; [isDisabled] receives one byte array per
 * EXISTING read path (all present) and must answer for exactly those.
 * Degenerate inputs follow mtbtool: a single-read feature treats an empty
 * array list as disabled (true), while a multi-read feature requires all N
 * arrays and reports not-disabled otherwise.
 *
 * 1:1 port of mtbtool's FeatureDef table (dev.henrik.mtbtool.FeatureDef).
 */
data class FeatureDef(
    val id: String,
    val label: String,
    val reads: List<String>,
    val writes: List<NvWrite>,
    val isDisabled: (List<List<Int>>) -> Boolean,
)

/** SIM1 item files carry this filename suffix (SIM0 has none). */
const val SUBSCRIPTION_SUFFIX = "_Subscription01"

/**
 * Maps a canonical (SIM0) item path to the selected SIM slot. SIM1 appends
 * [SUBSCRIPTION_SUFFIX] to the filename, idempotently.
 */
fun slotPath(path: String, slot: Int): String {
    require(slot == 0 || slot == 1)
    if (slot == 0) return path
    if (path.endsWith(SUBSCRIPTION_SUFFIX)) return path
    return path + SUBSCRIPTION_SUFFIX
}

private val NR_BASE = "/nv/item_files/modem/nr5g/RRC/"

/**
 * cap_feature_band_nr_v2 is a bitmap of enabled NR features: an absent item
 * means the modem defaults, which is what all-FF encodes.  NR 1024QAM for TDD
 * sits in bits 233-240, i.e. all eight bits of byte index 29 (1-based byte 30,
 * which is what ueCapabilityNR-r16 numbers them as).
 */
private val NR_FEATURE_BAND_ITEM = NR_BASE + "cap_feature_band_nr_v2"
private const val NR_1024QAM_TDD_BYTE = 29
private const val NR_FEATURE_BAND_LEN = 32

/** Bytes 0..28 are all-FF for a new item; byte 29 is the 1024QAM TDD switch. */
private val NR_1024QAM_TDD_DISABLED = List(NR_FEATURE_BAND_LEN) { if (it == NR_1024QAM_TDD_BYTE) 0 else 0xFF }

/**
 * Clears bits 233-240 of an existing bitmap and leaves every other bit as the
 * modem has it.  A shorter item is padded with FF (the default: feature on)
 * rather than refusing, since the tail it is missing is simply unset.
 */
private fun clearNr1024qamTdd(current: List<Int>?): List<Int> {
    val base = current?.toMutableList() ?: mutableListOf()
    while (base.size < NR_FEATURE_BAND_LEN) base += 0xFF
    base[NR_1024QAM_TDD_BYTE] = 0
    return base
}

/**
 * The NR5G capability toggles from mtbtool's features screen, plus the app's
 * own "Disable TDD-only UL MIMO" and "Disable NR 1024QAM TDD". For the ported
 * toggles the bytes and disabled-detection are byte-for-byte faithful to the
 * mtbtool source.  Do not "clean up" these payloads; each isDisabled must
 * recognize exactly what its writes produce.
 */
val ALL_FEATURES: List<FeatureDef> = listOf(
    FeatureDef(
        id = "r17_2t2t",
        label = "禁用 R17 2T2T 上行发射切换",
        reads = listOf(NR_BASE + "cap_control_nrca_xf_plus_yt_swul_r17_band_combos_v2"),
        writes = listOf(NvWrite(NR_BASE + "cap_control_nrca_xf_plus_yt_swul_r17_band_combos_v2", List(24) { 0 })),
        isDisabled = { byteArrays ->
            val b = byteArrays.firstOrNull() ?: return@FeatureDef true
            b.all { it == 0 }
        },
    ),
    FeatureDef(
        id = "r16_2t1t",
        label = "禁用 R16 2T1T 上行发射切换",
        reads = listOf(
            NR_BASE + "cap_control_nrca_xf_plus_yt_swul_band_combos_v2",
            NR_BASE + "cap_swul_type_control",
            NR_BASE + "cap_swul_control",
            NR_BASE + "cap_swul_3x_control",
            NR_BASE + "cap_swul_4x_control",
            NR_BASE + "cap_swul_5x_control",
        ),
        writes = listOf(
            NvWrite(NR_BASE + "cap_control_nrca_xf_plus_yt_swul_band_combos_v2", List(17) { 0 }),
            NvWrite(NR_BASE + "cap_swul_type_control", listOf(0, 0, 0)),
            NvWrite(NR_BASE + "cap_swul_control", listOf(0)),
            NvWrite(NR_BASE + "cap_swul_3x_control", listOf(0)),
            NvWrite(NR_BASE + "cap_swul_4x_control", listOf(0)),
            NvWrite(NR_BASE + "cap_swul_5x_control", listOf(0)),
        ),
        isDisabled = { byteArrays ->
            if (byteArrays.size < 6) return@FeatureDef false
            byteArrays.all { bytes -> bytes.all { it == 0 } }
        },
    ),
    FeatureDef(
        id = "ul_mimo",
        label = "禁用 上行 MIMO",
        reads = listOf(NR_BASE + "cap_limit_rf_mimo"),
        writes = listOf(NvWrite(NR_BASE + "cap_limit_rf_mimo", listOf(1, 1) + List(42) { 0 })),
        isDisabled = { byteArrays ->
            val b = byteArrays.firstOrNull() ?: return@FeatureDef true
            b.size >= 3 && b[0] == 1 && b[1] == 1 && b[2] == 0
        },
    ),
    FeatureDef(
        id = "fdd_ul_mimo",
        label = "禁用 仅 FDD 上行 MIMO",
        reads = listOf(NR_BASE + "cap_control_fdd_ul_mimo"),
        writes = listOf(NvWrite(NR_BASE + "cap_control_fdd_ul_mimo", listOf(0, 0))),
        isDisabled = { byteArrays ->
            val b = byteArrays.firstOrNull() ?: return@FeatureDef true
            b.size >= 2 && b[0] == 0 && b[1] == 0
        },
    ),
    FeatureDef(
        id = "tdd_ul_mimo",
        label = "禁用 仅 TDD 上行 MIMO",
        reads = listOf(NR_BASE + "cap_limit_rf_mimo"),
        writes = listOf(
            NvWrite(
                NR_BASE + "cap_limit_rf_mimo",
                // Padded to the 44-byte layout (4-byte header + 10 entries)
                // that ul_mimo and lowband_4rx write, as mtbtool does: a modem
                // may ignore an item file whose size does not match, and the
                // header's count already tells it the tail is unused.
                listOf(0, 0, 0, 8) +
                    listOf(
                        38, 0, 1, 0, 40, 0, 1, 0, 41, 0, 1, 0, 48, 0, 1, 0,
                        77, 0, 1, 0, 78, 0, 1, 0, 79, 0, 1, 0, 104, 0, 1, 0,
                    ) +
                    List(8) { 0 },
            ),
        ),
        isDisabled = { byteArrays ->
            val b = byteArrays.firstOrNull() ?: return@FeatureDef true
            val bandBytes =
                listOf(
                    38, 0, 1, 0, 40, 0, 1, 0, 41, 0, 1, 0, 48, 0, 1, 0,
                    77, 0, 1, 0, 78, 0, 1, 0, 79, 0, 1, 0, 104, 0, 1, 0,
                )
            b.size >= 36 &&
                b[0] == 0 && b[1] == 0 && b[2] == 0 && b[3] == 8 &&
                bandBytes.indices.all { i -> b[4 + i] == bandBytes[i] }
        },
    ),
    FeatureDef(
        id = "nr_1024qam_tdd",
        label = "禁用 NR TDD 1024QAM",
        reads = listOf(NR_FEATURE_BAND_ITEM),
        writes = listOf(
            NvWrite(
                NR_FEATURE_BAND_ITEM,
                // What a freshly created item gets: the modem default of every
                // feature on, with the 1024QAM TDD byte cleared.
                NR_1024QAM_TDD_DISABLED,
                merge = ::clearNr1024qamTdd,
            ),
        ),
        isDisabled = { byteArrays ->
            val b = byteArrays.firstOrNull() ?: return@FeatureDef true
            b.size > NR_1024QAM_TDD_BYTE && b[NR_1024QAM_TDD_BYTE] == 0
        },
    ),
    FeatureDef(
        id = "nr_ulca",
        label = "禁用 NR 上行载波聚合（UL-CA）",
        reads = listOf(
            NR_BASE + "cap_control_nrca_2x_f_plus_t_band_combos",
            NR_BASE + "cap_control_nrca_3x_f_plus_t_band_combos",
            NR_BASE + "cap_control_nrca_4x_f_plus_t_band_combos",
            NR_BASE + "cap_control_nrca_4x_f_plus_t_band_combos_v2",
            NR_BASE + "cap_control_nrca_f_plus_f_ulca_band_combos",
        ),
        writes = listOf(
            NvWrite(NR_BASE + "cap_control_nrca_2x_f_plus_t_band_combos", listOf(0)),
            NvWrite(NR_BASE + "cap_control_nrca_3x_f_plus_t_band_combos", listOf(1, 1, 1, 1, 0, 0)),
            NvWrite(NR_BASE + "cap_control_nrca_4x_f_plus_t_band_combos", listOf(0, 1, 1)),
            NvWrite(NR_BASE + "cap_control_nrca_4x_f_plus_t_band_combos_v2", listOf(0, 1, 1, 0, 1, 1)),
            NvWrite(NR_BASE + "cap_control_nrca_f_plus_f_ulca_band_combos", listOf(0, 0)),
        ),
        isDisabled = { byteArrays ->
            if (byteArrays.size < 5) return@FeatureDef false
            val (b0, b1, b2, b3, b4) = byteArrays
            b0.firstOrNull() == 0 &&
                b1.size >= 6 && b1[0] == 1 && b1[1] == 1 && b1[2] == 1 && b1[3] == 1 && b1[4] == 0 && b1[5] == 0 &&
                b2.size >= 3 && b2[0] == 0 && b2[1] == 1 && b2[2] == 1 &&
                b3.size >= 6 && b3[0] == 0 && b3[1] == 1 && b3[2] == 1 && b3[3] == 0 && b3[4] == 1 && b3[5] == 1 &&
                b4.size >= 2 && b4[0] == 0 && b4[1] == 0
        },
    ),
    FeatureDef(
        id = "dl_nrca",
        label = "禁用 NR 下行载波聚合（DL-CA）",
        reads = listOf(NR_BASE + "cap_nrca_downgrade_1cc"),
        writes = listOf(NvWrite(NR_BASE + "cap_nrca_downgrade_1cc", listOf(1))),
        isDisabled = { byteArrays ->
            val b = byteArrays.firstOrNull() ?: return@FeatureDef true
            b.firstOrNull() == 1
        },
    ),
    FeatureDef(
        id = "lowband_4rx",
        label = "禁用 低频段四路接收（4Rx）",
        reads = listOf(NR_BASE + "cap_limit_rf_mimo"),
        writes = listOf(
            NvWrite(
                NR_BASE + "cap_limit_rf_mimo",
                listOf(0, 0, 0, 5) +
                    listOf(8, 0, 0, 2, 20, 0, 0, 2, 26, 0, 0, 2, 28, 0, 0, 2, 71, 0, 0, 2) +
                    List(20) { 0 },
            ),
        ),
        isDisabled = { byteArrays ->
            val b = byteArrays.firstOrNull() ?: return@FeatureDef true
            val bandBytes = listOf(8, 0, 0, 2, 20, 0, 0, 2, 26, 0, 0, 2, 28, 0, 0, 2, 71, 0, 0, 2)
            b.size >= 24 &&
                b[0] == 0 && b[1] == 0 && b[2] == 0 && b[3] == 5 &&
                bandBytes.indices.all { i -> b[4 + i] == bandBytes[i] }
        },
    ),
    FeatureDef(
        id = "nsa_tf_nrca",
        label = "禁用 T+F NSA NR-CA",
        reads = listOf(
            NR_BASE + "cap_control_mrdc_f_plus_t_band_combos",
            NR_BASE + "cap_control_t_plus_f_band_combos",
        ),
        writes = listOf(
            NvWrite(NR_BASE + "cap_control_mrdc_f_plus_t_band_combos", listOf(0)),
            NvWrite(NR_BASE + "cap_control_t_plus_f_band_combos", listOf(7)),
        ),
        isDisabled = { byteArrays ->
            if (byteArrays.size < 2) return@FeatureDef false
            val (b0, b1) = byteArrays
            b0.firstOrNull() == 0 &&
                b1.firstOrNull() == 7
        },
    ),
    FeatureDef(
        id = "nsa_ff_nrca",
        label = "禁用 F+F NSA NR-CA",
        reads = listOf(NR_BASE + "cap_control_mrdc_2x_f_plus_f_band_combos"),
        writes = listOf(NvWrite(NR_BASE + "cap_control_mrdc_2x_f_plus_f_band_combos", listOf(0))),
        isDisabled = { byteArrays ->
            val b = byteArrays.firstOrNull() ?: return@FeatureDef true
            b.firstOrNull() == 0
        },
    ),
    FeatureDef(
        id = "nsa_tt_nrca",
        label = "禁用 T+T NSA NR-CA",
        reads = listOf(
            NR_BASE + "cap_control_mrdc_t_plus_t_band_combos",
            NR_BASE + "cap_control_nr_t_plus_t_band_combos",
        ),
        writes = listOf(
            NvWrite(NR_BASE + "cap_control_mrdc_t_plus_t_band_combos", listOf(0, 0, 0)),
            NvWrite(NR_BASE + "cap_control_nr_t_plus_t_band_combos", listOf(0, 0)),
        ),
        isDisabled = { byteArrays ->
            if (byteArrays.size < 2) return@FeatureDef false
            val (b0, b1) = byteArrays
            b0.size >= 3 && b0[0] == 0 && b0[1] == 0 && b0[2] == 0 &&
                b1.size >= 2 && b1[0] == 0 && b1[1] == 0
        },
    ),
    FeatureDef(
        id = "segmentation",
        label = "禁用 消息分段传输",
        reads = listOf(NR_BASE + "cap_msg_segmentation"),
        writes = listOf(NvWrite(NR_BASE + "cap_msg_segmentation", listOf(0))),
        isDisabled = { byteArrays ->
            val b = byteArrays.firstOrNull() ?: return@FeatureDef true
            b.firstOrNull() == 0
        },
    ),
    FeatureDef(
        id = "dss",
        label = "禁用 动态频谱共享（DSS）",
        reads = listOf(NR_BASE + "cap_dss_control"),
        writes = listOf(NvWrite(NR_BASE + "cap_dss_control", listOf(0, 0))),
        isDisabled = { byteArrays ->
            val b = byteArrays.firstOrNull() ?: return@FeatureDef true
            b.size >= 2 && b[0] == 0 && b[1] == 0
        },
    ),
)

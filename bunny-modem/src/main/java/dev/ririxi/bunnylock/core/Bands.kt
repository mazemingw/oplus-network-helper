package dev.ririxi.bunnylock.core

import kotlin.math.roundToInt

/**
 * Modem masks map band N to bit (N-1), as little-endian bytes (Qualcomm QMI, Pixel NV)
 * or 32-bit words (MediaTek EPBSE). The words are unsigned, so they are held in Longs.
 */
object Bands {
    val LTE: List<Int> =
        listOf(
            1,
            2,
            3,
            4,
            5,
            7,
            8,
            11,
            12,
            13,
            14,
            17,
            18,
            19,
            20,
            21,
            25,
            26,
            28,
            29,
            30,
            31,
            32,
            34,
            38,
            39,
            40,
            41,
            42,
            43,
            46,
            48,
            66,
            67,
            68,
            70,
            71,
            72,
            73,
            74,
            85,
            87,
            88,
            103,
            106,
        )

    val NR: List<Int> =
        listOf(
            1,
            2,
            3,
            5,
            7,
            8,
            12,
            13,
            14,
            18,
            20,
            24,
            25,
            26,
            28,
            29,
            30,
            34,
            38,
            39,
            40,
            41,
            46,
            47,
            48,
            50,
            51,
            53,
            65,
            66,
            67,
            70,
            71,
            74,
            75,
            76,
            77,
            78,
            79,
            85,
            90,
            91,
            92,
            93,
            94,
            96,
            100,
            101,
            102,
            104,
            105,
            106,
            257,
            258,
            259,
            260,
            261,
            262,
        )

    /** GSM bands by their MHz name. */
    val GSM: List<Int> = listOf(850, 900, 1800, 1900)

    val WCDMA: List<Int> = (1..19).toList()

    /** What people usually call each band, in MHz unless it says GHz. */
    val LTE_FREQUENCY_NAMES: Map<Int, String> =
        mapOf(
            1 to "2100",
            2 to "1900",
            3 to "1800",
            4 to "1700",
            5 to "850",
            7 to "2600",
            8 to "900",
            11 to "1500",
            12 to "700",
            13 to "700",
            14 to "700",
            17 to "700",
            18 to "800",
            19 to "800",
            20 to "800",
            21 to "1500",
            25 to "1900",
            26 to "850",
            28 to "700",
            29 to "700",
            30 to "2300",
            31 to "450",
            32 to "1500",
            34 to "2000",
            38 to "2600",
            39 to "1900",
            40 to "2300",
            41 to "2500",
            42 to "3500",
            43 to "3700",
            46 to "5 GHz",
            48 to "3600",
            66 to "1700",
            67 to "700",
            68 to "700",
            70 to "2000",
            71 to "600",
            72 to "450",
            73 to "450",
            74 to "1500",
            85 to "700",
            87 to "410",
            88 to "410",
            103 to "700",
            106 to "900",
        )

    val NR_FREQUENCY_NAMES: Map<Int, String> =
        mapOf(
            1 to "2100",
            2 to "1900",
            3 to "1800",
            5 to "850",
            7 to "2600",
            8 to "900",
            12 to "700",
            13 to "700",
            14 to "700",
            18 to "800",
            20 to "800",
            24 to "1500",
            25 to "1900",
            26 to "850",
            28 to "700",
            29 to "700",
            30 to "2300",
            34 to "2000",
            38 to "2600",
            39 to "1900",
            40 to "2300",
            41 to "2500",
            46 to "5 GHz",
            47 to "5.9 GHz",
            48 to "3600",
            50 to "1500",
            51 to "1500",
            53 to "2500",
            65 to "2100",
            66 to "1700",
            67 to "700",
            70 to "2000",
            71 to "600",
            74 to "1500",
            75 to "1500",
            76 to "1500",
            77 to "3700",
            78 to "3500",
            79 to "4700",
            85 to "700",
            90 to "2500",
            91 to "1500",
            92 to "1500",
            93 to "1500",
            94 to "1500",
            96 to "6 GHz",
            100 to "900",
            101 to "1900",
            102 to "6 GHz",
            104 to "6.4 GHz",
            105 to "600",
            106 to "900",
            257 to "28 GHz",
            258 to "26 GHz",
            259 to "41 GHz",
            260 to "39 GHz",
            261 to "28 GHz",
            262 to "47 GHz",
        )

    val WCDMA_FREQUENCY_NAMES: Map<Int, String> =
        mapOf(
            1 to "2100",
            2 to "1900",
            3 to "1800",
            4 to "1700",
            5 to "850",
            6 to "800",
            7 to "2600",
            8 to "900",
            9 to "1800",
            10 to "1700",
            11 to "1500",
            12 to "700",
            13 to "700",
            14 to "700",
            19 to "800",
        )

    fun toMask(
        bands: Collection<Int>,
        bytes: Int,
    ): ByteArray {
        val mask = ByteArray(bytes)
        for (band in bands) {
            val bit = band - 1
            val byteIndex = bit / 8
            if (bit < 0 || byteIndex >= bytes) continue
            mask[byteIndex] = (mask[byteIndex].toInt() or (1 shl (bit % 8))).toByte()
        }
        return mask
    }

    fun fromMask(mask: ByteArray): List<Int> {
        val bands = ArrayList<Int>()
        for (byteIndex in mask.indices) {
            val bits = mask[byteIndex].toInt() and 0xFF
            for (bit in 0 until 8) {
                if ((bits and (1 shl bit)) != 0) bands += byteIndex * 8 + bit + 1
            }
        }
        return bands
    }

    fun toWords(
        bands: Collection<Int>,
        words: Int,
    ): LongArray {
        val mask = LongArray(words)
        for (band in bands) {
            val bit = band - 1
            val wordIndex = bit / 32
            if (bit < 0 || wordIndex >= words) continue
            mask[wordIndex] = mask[wordIndex] or (1L shl (bit % 32))
        }
        return mask
    }

    fun fromWords(words: LongArray): List<Int> {
        val bands = ArrayList<Int>()
        for (wordIndex in words.indices) {
            for (bit in 0 until 32) {
                if ((words[wordIndex] and (1L shl bit)) != 0L) bands += wordIndex * 32 + bit + 1
            }
        }
        return bands
    }

    /**
     * One band's downlink row of 3GPP TS 36.101 table 5.7.3-1: its EARFCN range and the carrier frequency
     * of the first EARFCN (F_DL_low). Each following EARFCN is 0.1 MHz higher.
     */
    private data class LteRange(
        val band: Int,
        val lowestDownlinkMhz: Double,
        val firstEarfcn: Int,
        val lastEarfcn: Int,
    ) {
        /** In 0.1 MHz steps, so that frequencies of different bands compare as whole numbers. */
        val lowestDownlinkTenthsMhz: Int get() = (lowestDownlinkMhz * 10).roundToInt()
    }

    // Columns: band, F_DL_low in MHz, first EARFCN (N_Offs-DL), last EARFCN.
    private val lteDownlinkRanges =
        listOf(
            LteRange(1, 2110.0, 0, 599),
            LteRange(2, 1930.0, 600, 1199),
            LteRange(3, 1805.0, 1200, 1949),
            LteRange(4, 2110.0, 1950, 2399),
            LteRange(5, 869.0, 2400, 2649),
            LteRange(7, 2620.0, 2750, 3449),
            LteRange(8, 925.0, 3450, 3799),
            LteRange(11, 1475.9, 4750, 4949),
            LteRange(12, 729.0, 5010, 5179),
            LteRange(13, 746.0, 5180, 5279),
            LteRange(14, 758.0, 5280, 5379),
            LteRange(17, 734.0, 5730, 5849),
            LteRange(18, 860.0, 5850, 5999),
            LteRange(19, 875.0, 6000, 6149),
            LteRange(20, 791.0, 6150, 6449),
            LteRange(21, 1495.9, 6450, 6599),
            LteRange(25, 1930.0, 8040, 8689),
            LteRange(26, 859.0, 8690, 9039),
            LteRange(28, 758.0, 9210, 9659),
            LteRange(29, 717.0, 9660, 9769),
            LteRange(30, 2350.0, 9770, 9869),
            LteRange(31, 462.5, 9870, 9919),
            LteRange(32, 1452.0, 9920, 10359),
            LteRange(34, 2010.0, 36200, 36349),
            LteRange(38, 2570.0, 37750, 38249),
            LteRange(39, 1880.0, 38250, 38649),
            LteRange(40, 2300.0, 38650, 39649),
            LteRange(41, 2496.0, 39650, 41589),
            LteRange(42, 3400.0, 41590, 43589),
            LteRange(43, 3600.0, 43590, 45589),
            LteRange(46, 5150.0, 46790, 54539),
            LteRange(48, 3550.0, 55240, 56739),
            LteRange(66, 2110.0, 66436, 67335),
            LteRange(67, 738.0, 67336, 67535),
            LteRange(68, 753.0, 67536, 67835),
            LteRange(70, 1995.0, 68336, 68585),
            LteRange(71, 617.0, 68586, 68935),
            LteRange(72, 461.0, 68936, 68985),
            LteRange(73, 460.0, 68986, 69035),
            LteRange(74, 1475.0, 69036, 69465),
            LteRange(85, 728.0, 70366, 70545),
            LteRange(87, 420.0, 70546, 70595),
            LteRange(88, 422.0, 70596, 70645),
            LteRange(103, 757.0, 70646, 70655),
            LteRange(106, 935.0, 70656, 70705),
        )

    private fun lteRangeOf(earfcn: Int): LteRange? =
        lteDownlinkRanges.firstOrNull { earfcn in it.firstEarfcn..it.lastEarfcn }

    fun lteBandOf(earfcn: Int): Int? = lteRangeOf(earfcn)?.band

    fun lteFreqMhz(earfcn: Int): Double? =
        lteRangeOf(earfcn)?.let { it.lowestDownlinkMhz + 0.1 * (earfcn - it.firstEarfcn) }

    // Bands that overlap with the same duplex spacing, so a cell in one can be camped on as another (36.331 multiBandInfoList).
    private val lteOverlappingBandGroups =
        listOf(
            listOf(2, 25),
            listOf(4, 66),
            listOf(5, 18, 19, 26),
            listOf(12, 17, 85),
            listOf(38, 41),
            listOf(42, 43, 48)
        )

    /** The bands a phone can use this EARFCN's cell as: its own, plus any that share cells with it at this frequency. */
    fun lteBandsAt(earfcn: Int): List<Int> {
        val own = lteRangeOf(earfcn) ?: return emptyList()
        val overlapping = lteOverlappingBandGroups.firstOrNull { own.band in it } ?: return listOf(own.band)
        val carrierTenthsMhz = own.lowestDownlinkTenthsMhz + earfcn - own.firstEarfcn
        return lteDownlinkRanges
            .filter { range ->
                val stepsAboveLowest = carrierTenthsMhz - range.lowestDownlinkTenthsMhz
                range.band in overlapping && stepsAboveLowest in 0..(range.lastEarfcn - range.firstEarfcn)
            }.map { it.band }
    }

    private data class NrRange(
        val band: Int,
        val firstArfcn: Int,
        val lastArfcn: Int,
    )

    // 3GPP TS 38.104 tables 5.4.2.3-1 (FR1) and 5.4.2.3-2 (FR2). Columns: band, first and last downlink NR-ARFCN.
    private val nrDownlinkRanges =
        listOf(
            NrRange(1, 422000, 434000),
            NrRange(2, 386000, 398000),
            NrRange(3, 361000, 376000),
            NrRange(5, 173800, 178800),
            NrRange(7, 524000, 538000),
            NrRange(8, 185000, 192000),
            NrRange(12, 145800, 149200),
            NrRange(13, 149200, 151200),
            NrRange(14, 151600, 153600),
            NrRange(18, 172000, 175000),
            NrRange(20, 158200, 164200),
            NrRange(24, 305000, 311800),
            NrRange(25, 386000, 399000),
            NrRange(26, 171800, 178800),
            NrRange(28, 151600, 160600),
            NrRange(29, 143400, 145600),
            NrRange(30, 470000, 472000),
            NrRange(34, 402000, 405000),
            NrRange(38, 514000, 524000),
            NrRange(39, 376000, 384000),
            NrRange(40, 460000, 480000),
            NrRange(41, 499200, 537999),
            NrRange(46, 743334, 795000),
            NrRange(47, 790334, 795000),
            NrRange(48, 636667, 646666),
            NrRange(50, 286400, 303400),
            NrRange(51, 285400, 286400),
            NrRange(53, 496700, 499000),
            NrRange(65, 422000, 440000),
            NrRange(66, 422000, 440000),
            NrRange(67, 147600, 151600),
            NrRange(70, 399000, 404000),
            NrRange(71, 123400, 130400),
            NrRange(74, 295000, 303600),
            NrRange(75, 286400, 303400),
            NrRange(76, 285400, 286400),
            NrRange(77, 620000, 680000),
            NrRange(78, 620000, 653333),
            NrRange(79, 693334, 733333),
            NrRange(85, 145600, 149200),
            NrRange(90, 499200, 538000),
            NrRange(91, 285400, 286400),
            NrRange(92, 286400, 303400),
            NrRange(93, 285400, 286400),
            NrRange(94, 286400, 303400),
            NrRange(96, 795000, 875000),
            NrRange(100, 183880, 185000),
            NrRange(101, 380000, 382000),
            NrRange(102, 795000, 828333),
            NrRange(104, 828334, 875000),
            NrRange(105, 122400, 130400),
            NrRange(106, 187000, 188000),
            NrRange(257, 2054166, 2104165),
            NrRange(258, 2016667, 2070832),
            NrRange(259, 2270833, 2337499),
            NrRange(260, 2229166, 2279165),
            NrRange(261, 2070833, 2084999),
            NrRange(262, 2399166, 2415832),
        )

    // NR bands overlap, so one ARFCN can belong to several of them. Bands listed here are offered first, in this order.
    private val nrPreferred = listOf(78, 41, 77, 1, 3, 28, 7, 5, 8, 20, 71, 66, 2, 25, 79, 40, 38, 260, 261, 258, 257)

    private fun nrPreferenceRank(band: Int): Int {
        val index = nrPreferred.indexOf(band)
        return if (index < 0) Int.MAX_VALUE else index
    }

    /** Most common band first. */
    fun nrBandsOf(arfcn: Int): List<Int> =
        nrDownlinkRanges.filter { arfcn in it.firstArfcn..it.lastArfcn }.map { it.band }.sortedBy(::nrPreferenceRank)

    private data class ChannelRange(
        val band: Int,
        val channels: IntRange,
    )

    private val wcdmaDownlinkRanges =
        listOf(
            ChannelRange(1, 10562..10838),
            ChannelRange(2, 9662..9938),
            ChannelRange(3, 1162..1513),
            ChannelRange(4, 1537..1738),
            ChannelRange(5, 4357..4458),
            ChannelRange(6, 4387..4413),
            ChannelRange(7, 2237..2563),
            ChannelRange(8, 2937..3088),
            ChannelRange(9, 9237..9387),
            ChannelRange(10, 3112..3388),
            ChannelRange(11, 3712..3787),
            ChannelRange(12, 3842..3903),
            ChannelRange(13, 4017..4043),
            ChannelRange(14, 4117..4143),
            ChannelRange(19, 712..763),
            ChannelRange(20, 4512..4638),
            ChannelRange(21, 862..912),
            ChannelRange(22, 4662..5038),
            ChannelRange(25, 5112..5413),
        )

    private val gsmRanges =
        listOf(
            ChannelRange(850, 128..251),
            ChannelRange(900, 0..124),
            ChannelRange(900, 955..1023),
            ChannelRange(1800, 512..885),
            ChannelRange(1900, 512..810),
        )

    /** Band 6 lies inside band 5, so band 5 comes first. */
    fun wcdmaBandsAt(uarfcn: Int): List<Int> = wcdmaDownlinkRanges.filter { uarfcn in it.channels }.map { it.band }

    fun gsmBandsAt(arfcn: Int): List<Int> = gsmRanges.filter { arfcn in it.channels }.map { it.band }.distinct()

    // 3GPP TS 38.104 table 5.4.2.1-1: the raster is 5 kHz below 3 GHz, 15 kHz from there to 24.25 GHz and 60 kHz above.
    fun nrFreqMhz(arfcn: Int): Double =
        when {
            arfcn < 600000 -> arfcn * 0.005
            arfcn < 2016667 -> 3000.0 + (arfcn - 600000) * 0.015
            else -> 24250.08 + (arfcn - 2016667) * 0.06
        }

    /** Typical SSB subcarrier spacing in kHz. */
    fun nrDefaultScs(band: Int): Int =
        when (band) {
            in 257..262 -> 120
            41, 46, 48, 77, 78, 79, 90, 96, 102, 104 -> 30
            else -> 15
        }
}

/** Values of android.telephony.AccessNetworkConstants.AccessNetworkType. */
object Ran {
    const val GERAN = 1
    const val UTRAN = 2
    const val EUTRAN = 3
    const val NGRAN = 6
}

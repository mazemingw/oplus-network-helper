package dev.ririxi.bunnylock.data

import android.annotation.SuppressLint
import android.content.Context
import android.telephony.CellIdentityGsm
import android.telephony.CellIdentityLte
import android.telephony.CellIdentityNr
import android.telephony.CellIdentityWcdma
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.CellSignalStrengthNr
import android.telephony.TelephonyManager
import dev.ririxi.bunnylock.core.Bands
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Declared oldest first: the cell list relies on this order to put newer technologies on top. */
enum class Rat { GSM, WCDMA, LTE, NR }

fun Rat.bandLabel(band: Int?): String =
    when {
        band === null -> "?"
        this == Rat.NR -> "n$band"
        this == Rat.LTE || this == Rat.WCDMA -> "B$band"
        else -> "$band"
    }

fun Rat.frequencyName(band: Int?): String? =
    band?.let { number ->
        when (this) {
            Rat.LTE -> Bands.LTE_FREQUENCY_NAMES[number]
            Rat.NR -> Bands.NR_FREQUENCY_NAMES[number]
            Rat.WCDMA -> Bands.WCDMA_FREQUENCY_NAMES[number]
            Rat.GSM -> null
        }
    }

internal fun Int.availableOrNull(): Int? = takeIf { it != CellInfo.UNAVAILABLE }

data class Cell(
    val rat: Rat,
    val arfcn: Long,
    /** Physical cell id for LTE and NR. For WCDMA it is the PSC and for GSM the BSIC. -1 when unknown. */
    val pci: Int,
    val band: Int?,
    val registered: Boolean,
    /** In dBm. For WCDMA and GSM it is the plain signal strength Android reports rather than an RSRP. */
    val rsrp: Int?,
    /** "android" or "modem" (QMI report, includes the NSA SCG). */
    val source: String,
    val note: String = "",
    /** MCC+MNC digits, e.g. "26201". Usually only known for the serving cell. */
    val plmn: String? = null,
    val operator: String? = null,
    /** Only LTE cells report it, and usually only the serving one. */
    val bandwidthKhz: Int? = null,
) {
    val key get() = "$rat:$arfcn:$pci"

    val bandLabel get() = rat.bandLabel(band)

    val frequencyName get() = rat.frequencyName(band)
}

object Cells {
    private val callbackExecutor = Executors.newSingleThreadExecutor()

    // -200 dBm is below anything a modem reports, so cells without a reading come last.
    private val displayOrder =
        compareByDescending<Cell> { it.registered }
            .thenByDescending { it.rat.ordinal }
            .thenByDescending { it.rsrp ?: -200 }

    @SuppressLint("MissingPermission")
    suspend fun scan(
        context: Context,
        subId: Int,
        freshWait: Duration = 4.seconds,
    ): List<Cell> {
        val defaultTelephony = context.getSystemService(TelephonyManager::class.java) ?: return emptyList()
        val telephony = if (subId >= 0) defaultTelephony.createForSubscriptionId(subId) else defaultTelephony
        // The refresh callback isn't guaranteed to arrive, so the wait is capped and an earlier list is used instead.
        val fresh = withTimeoutOrNull(freshWait) { freshCellInfo(telephony) }
        if (fresh !== null) lastCellInfo[subId] = fresh
        val cellInfos = fresh ?: lastCellInfo[subId] ?: cachedCellInfo(telephony)
        return cellInfos.mapNotNull(::toCell)
    }

    private val lastCellInfo = ConcurrentHashMap<Int, List<CellInfo>>()

    @SuppressLint("MissingPermission")
    private suspend fun freshCellInfo(telephony: TelephonyManager): List<CellInfo>? =
        suspendCancellableCoroutine { cont ->
            try {
                telephony.requestCellInfoUpdate(
                    callbackExecutor,
                    object : TelephonyManager.CellInfoCallback() {
                        override fun onCellInfo(cellInfo: MutableList<CellInfo>) {
                            if (cont.isActive) cont.resume(cellInfo)
                        }

                        override fun onError(
                            errorCode: Int,
                            detail: Throwable?,
                        ) {
                            if (cont.isActive) cont.resume(null)
                        }
                    },
                )
            } catch (_: SecurityException) {
                if (cont.isActive) cont.resume(emptyList())
            }
        }

    /** Android's cached list mixes the cells of every SIM, so it's only used when one modem is active. */
    @SuppressLint("MissingPermission")
    private fun cachedCellInfo(telephony: TelephonyManager): List<CellInfo> {
        if (telephony.activeModemCount > 1) return emptyList()
        return runCatching { telephony.allCellInfo }.getOrNull() ?: emptyList()
    }

    private fun plmnOf(
        mcc: String?,
        mnc: String?,
    ) = if (mcc.isNullOrEmpty() || mnc.isNullOrEmpty()) null else mcc + mnc

    private fun toCell(info: CellInfo): Cell? {
        val cell = baseCell(info) ?: return null
        val identity = info.cellIdentity
        val plmn =
            when (identity) {
                is CellIdentityLte -> plmnOf(identity.mccString, identity.mncString)
                is CellIdentityNr -> plmnOf(identity.mccString, identity.mncString)
                is CellIdentityWcdma -> plmnOf(identity.mccString, identity.mncString)
                is CellIdentityGsm -> plmnOf(identity.mccString, identity.mncString)
                else -> null
            }
        return cell.copy(plmn = plmn, operator = identity.operatorAlphaLong?.toString()?.takeIf { it.isNotBlank() })
    }

    // Some radio HALs report a wrong band (e.g. n1 for an n78 ARFCN), so the ARFCN table wins on disagreement.
    internal fun lteBand(
        reported: IntArray,
        earfcn: Int,
    ): Int? = Bands.lteBandOf(earfcn) ?: reported.firstOrNull()

    internal fun nrBand(
        reported: IntArray,
        arfcn: Int,
    ): Int? {
        val candidates = Bands.nrBandsOf(arfcn)
        return reported.firstOrNull { it in candidates } ?: candidates.firstOrNull() ?: reported.firstOrNull()
    }

    private fun baseCell(info: CellInfo): Cell? =
        when (info) {
            is CellInfoLte -> lteCell(info)
            is CellInfoNr -> nrCell(info)
            is CellInfoWcdma -> wcdmaCell(info)
            is CellInfoGsm -> gsmCell(info)
            else -> null
        }

    private fun lteCell(info: CellInfoLte): Cell? {
        val identity = info.cellIdentity
        val earfcn = identity.earfcn
        if (earfcn == CellInfo.UNAVAILABLE || identity.pci == CellInfo.UNAVAILABLE) return null
        return Cell(
            Rat.LTE,
            earfcn.toLong(),
            identity.pci,
            lteBand(if (android.os.Build.VERSION.SDK_INT >= 30) identity.bands else intArrayOf(), earfcn),
            registered = info.isRegistered,
            rsrp = info.cellSignalStrength.rsrp.availableOrNull(),
            source = "android",
            bandwidthKhz = identity.bandwidth.availableOrNull(),
        )
    }

    private fun nrCell(info: CellInfoNr): Cell? {
        val identity = info.cellIdentity as CellIdentityNr
        val signal = info.cellSignalStrength as CellSignalStrengthNr
        val nrarfcn = identity.nrarfcn
        if (nrarfcn == CellInfo.UNAVAILABLE || identity.pci == CellInfo.UNAVAILABLE) return null
        return Cell(
            Rat.NR,
            nrarfcn.toLong(),
            identity.pci,
            nrBand(if (android.os.Build.VERSION.SDK_INT >= 30) identity.bands else intArrayOf(), nrarfcn),
            registered = info.isRegistered,
            rsrp = signal.ssRsrp.availableOrNull(),
            source = "android",
        )
    }

    private fun wcdmaCell(info: CellInfoWcdma): Cell? {
        val identity = info.cellIdentity
        if (identity.uarfcn == CellInfo.UNAVAILABLE) return null
        return Cell(
            Rat.WCDMA,
            identity.uarfcn.toLong(),
            pci = identity.psc.availableOrNull() ?: -1,
            band = Bands.wcdmaBandsAt(identity.uarfcn).firstOrNull(),
            registered = info.isRegistered,
            rsrp = info.cellSignalStrength.dbm.availableOrNull(),
            source = "android",
        )
    }

    private fun gsmCell(info: CellInfoGsm): Cell? {
        val identity = info.cellIdentity
        if (identity.arfcn == CellInfo.UNAVAILABLE) return null
        return Cell(
            Rat.GSM,
            identity.arfcn.toLong(),
            pci = identity.bsic.availableOrNull() ?: -1,
            band = Bands.gsmBandsAt(identity.arfcn).singleOrNull(),
            registered = info.isRegistered,
            rsrp = info.cellSignalStrength.dbm.availableOrNull(),
            source = "android",
        )
    }

    private fun JSONObject.rsrpOrNull(): Int? = optDouble("rsrp", Double.NaN).takeIf { !it.isNaN() }?.toInt()

    /** The qc.serving report is the only source that exposes the NSA SCG. */
    fun fromQualcomm(report: JSONObject): List<Cell> {
        val cells = ArrayList<Cell>()
        report.optJSONObject("lte")?.let { lte ->
            val earfcn = lte.optLong("earfcn", -1)
            if (earfcn >= 0) {
                cells +=
                    Cell(
                        Rat.LTE,
                        earfcn,
                        lte.optInt("pci"),
                        Bands.lteBandOf(earfcn.toInt()),
                        registered = true,
                        rsrp = lte.rsrpOrNull(),
                        source = "modem",
                        note = "LTE anchor (PCell)",
                    )
            }
        }
        report.optJSONObject("nr")?.let { nr ->
            val arfcn = nr.optLong("arfcn", -1)
            if (arfcn > 0) {
                val reportedBands = if (nr.has("band")) intArrayOf(nr.getInt("band")) else IntArray(0)
                cells +=
                    Cell(
                        Rat.NR,
                        arfcn,
                        nr.optInt("pci", -1),
                        nrBand(reportedBands, arfcn.toInt()),
                        registered = true,
                        rsrp = nr.rsrpOrNull(),
                        source = "modem",
                        note = if (report.has("lte")) "NSA SCG" else "SA PCell",
                    )
            }
        }
        report.optJSONArray("lteNeighbors")?.let { neighbours ->
            for (i in 0 until neighbours.length()) {
                val neighbour = neighbours.getJSONObject(i)
                val earfcn = neighbour.optLong("earfcn")
                cells +=
                    Cell(
                        Rat.LTE,
                        earfcn,
                        neighbour.optInt("pci"),
                        Bands.lteBandOf(earfcn.toInt()),
                        registered = false,
                        rsrp = neighbour.rsrpOrNull(),
                        source = "modem",
                        note = "neighbour",
                    )
            }
        }
        return cells
    }

    /**
     * Joins both lists in display order. A cell that appears twice (same RAT, channel and PCI) becomes one entry:
     * the values of its first appearance are kept, so [primary] wins, and later ones only fill in what is missing.
     */
    fun merge(
        primary: List<Cell>,
        secondary: List<Cell>,
    ): List<Cell> {
        val byKey = LinkedHashMap<String, Cell>()
        for (cell in primary + secondary) {
            val earlier = byKey[cell.key]
            byKey[cell.key] = earlier?.copy(
                registered = earlier.registered || cell.registered,
                rsrp = earlier.rsrp ?: cell.rsrp,
                note = earlier.note.ifEmpty { cell.note },
                band = earlier.band ?: cell.band,
                plmn = earlier.plmn ?: cell.plmn,
                operator = earlier.operator ?: cell.operator,
                bandwidthKhz = earlier.bandwidthKhz ?: cell.bandwidthKhz,
            ) ?: cell
        }
        return byKey.values.sortedWith(displayOrder)
    }
}

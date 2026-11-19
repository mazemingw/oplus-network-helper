package dev.ririxi.bunnylock.backend

import dev.ririxi.bunnylock.core.Bands
import dev.ririxi.bunnylock.data.Carrier
import dev.ririxi.bunnylock.data.Cell
import dev.ririxi.bunnylock.data.Rat
import dev.ririxi.bunnylock.modem.BandGroupState
import dev.ririxi.bunnylock.modem.Group

internal fun ratOf(group: Group) =
    when (group) {
        Group.GSM -> Rat.GSM
        Group.WCDMA -> Rat.WCDMA
        Group.LTE -> Rat.LTE
        Group.NR, Group.NR_SA, Group.NR_NSA -> Rat.NR
    }

// A GSM channel from 512 to 810 may be 1800 or 1900, so its cell has no band of its own.
internal fun Cell.isOnAnyOf(bands: List<Int>): Boolean = if (band !== null) band in bands else bandsAt(this).any { it in bands }

private fun bandsAt(cell: Cell): List<Int> {
    val channel = cell.arfcn.toInt()
    return when (cell.rat) {
        Rat.GSM -> Bands.gsmBandsAt(channel)
        Rat.WCDMA -> Bands.wcdmaBandsAt(channel)
        Rat.LTE -> Bands.lteBandsAt(channel)
        Rat.NR -> Bands.nrBandsOf(channel)
    }
}

internal fun Carrier.nrCoveringBands(): List<Int> = arfcn?.let { Bands.nrBandsOf(it.toInt()) }.orEmpty().ifEmpty { listOfNotNull(band) }

// The HAL only says that Android took a lock, so a serving cell that no locked band can reach is the one sign the modem ignores it.
// A 5G NSA cell never counts as registered, so a 5G carrier on another band is that sign too.
internal fun strayBand(
    cells: List<Cell>,
    group: Group,
    lockedBands: Collection<Int>,
    carriers: List<Carrier> = emptyList(),
): Int? {
    val rat = ratOf(group)
    val strayCell =
        cells
            .filter { it.registered && it.rat == rat }
            .firstOrNull { cell ->
                val coveringBands = bandsAt(cell)
                coveringBands.isNotEmpty() && coveringBands.none { it in lockedBands }
            }
    if (strayCell !== null) return strayCell.band ?: bandsAt(strayCell).first()
    if (rat != Rat.NR) return null
    return carriers.filter { it.rat == Rat.NR }.firstNotNullOfOrNull { carrier ->
        val coveringBands = carrier.nrCoveringBands()
        if (coveringBands.isNotEmpty() && coveringBands.none { it in lockedBands }) carrier.band ?: coveringBands.first() else null
    }
}

internal fun withStrayBands(
    groups: List<BandGroupState>,
    lteLock: String?,
    nrLock: String?,
    cells: List<Cell>,
    carriers: List<Carrier>,
): List<BandGroupState> =
    groups.map { groupState ->
        // A HAL band lock stands by while a cell lock holds its network, and that cell may be on another band.
        val cellLock =
            when (groupState.group) {
                Group.LTE -> lteLock
                Group.NR -> nrLock
                else -> null
            }
        val standingBy = cellLock !== null
        val stray =
            if (groupState.locked && !standingBy) strayBand(cells, groupState.group, groupState.current.orEmpty(), carriers) else null
        groupState.copy(stray = stray)
    }

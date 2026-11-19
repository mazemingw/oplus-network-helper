package dev.ririxi.bunnylock.backend.qualcomm

import dev.ririxi.bunnylock.core.Bands
import dev.ririxi.bunnylock.data.Cell
import dev.ririxi.bunnylock.data.Cells
import dev.ririxi.bunnylock.data.Rat
import org.json.JSONObject

private fun JSONObject.rsrpOrNull(): Int? = optDouble("rsrp", Double.NaN).takeIf { !it.isNaN() }?.toInt()

// The qc.serving report is the only source that exposes the NSA SCG.
internal fun qualcommCells(report: JSONObject): List<Cell> {
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
                    Cells.nrBand(reportedBands, arfcn.toInt()),
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

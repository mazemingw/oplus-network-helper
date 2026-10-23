package com.nvmex.networkhelper.util.map

import com.nvmex.networkhelper.model.menu.LteCellQueryResp
import com.nvmex.networkhelper.network.map.LteCellParam
import com.nvmex.networkhelper.network.map.LteSiteQueryResp
import com.nvmex.networkhelper.network.model.NrCellParamItem
import com.nvmex.networkhelper.network.model.NrSiteQueryResp

internal fun LteCellQueryResp.stationRows(): List<LteCellParam> = data.map { row ->
    LteCellParam(id = row.id, tac = row.tac, pci = row.pci, enodeb_id = row.enodeb_id,
        earfcn = row.earfcn, cell_id = row.cell_id, eci = row.eci, cell_name = row.cell_name,
        sector_id = row.sector_id, longitude = row.longitude, latitude = row.latitude,
        azimuth = row.azimuth, site_type = row.site_type, antenna_height = row.antenna_height, source = row.source)
}

// Some server versions supply the matched cell only in anchor, outside the expanded sector data.
internal fun LteSiteQueryResp.stationRows(): List<LteCellParam> = (listOfNotNull(anchor) + data).distinct()

internal fun NrSiteQueryResp.stationRows(): List<NrCellParamItem> = (listOfNotNull(anchor?.let {
    NrCellParamItem(id = it.id, gcell_id = it.gcell_id, cell_name = it.cell_name,
        longitude = it.longitude, latitude = it.latitude, azimuth = it.azimuth,
        nr_pci = it.nr_pci, nr_arfcn = it.nr_arfcn, nr_tac = it.nr_tac, site_type = it.site_type, source = it.source)
}) + data).distinct()

package com.nvmex.networkhelper.util.map

import LteCellOverlayManager
import NrCellOverlayManager
import android.graphics.Color
import android.os.SystemClock
import com.amap.api.maps.AMap
import com.amap.api.maps.model.*
import com.nvmex.networkhelper.R
import com.nvmex.networkhelper.model.map.DrivePoint
import com.nvmex.networkhelper.model.menu.LteCellQueryBody
import com.nvmex.networkhelper.model.network.NetworkPanelUiState
import com.nvmex.networkhelper.network.api.ApiService
import com.nvmex.networkhelper.network.model.NrQueryReq
import com.nvmex.networkhelper.repository.CellParamLocalRepository
import kotlinx.coroutines.*
import retrofit2.HttpException
import retrofit2.Response
import java.util.Locale

/** One selected SIM/RAT at a time. A nearby or approximate site is never called the serving site. */
internal class ServingStationOverlay(
    private val map: AMap, private val api: ApiService,
    private val local: CellParamLocalRepository, private val scope: CoroutineScope,
    private val lte: LteCellOverlayManager, private val nr: NrCellOverlayManager,
    private val onStatus: (Int, String?) -> Unit,
    private val onLinked: (NetworkPanelUiState, DriveStationSnapshot, LatLng) -> Unit = { _, _, _ -> }
) {
    private var key: String? = null
    private var queryAt = 0L
    private var job: Job? = null
    private var sitePosition: LatLng? = null
    private var userPosition: LatLng? = null
    private var line: Polyline? = null
    private var distanceLabel: Text? = null
    private var showLink = true
    private var redraw: (() -> Unit)? = null
    private var linkedState: NetworkPanelUiState? = null
    private var linkedSnapshot: DriveStationSnapshot? = null
    private var history: List<Pair<DriveStationEntity, DriveStationSnapshot>>? = null
    private var historyCurrent: Pair<DriveStationEntity, DriveStationSnapshot>? = null
    private var historyPosition: LatLng? = null
    private val historyLines = mutableListOf<Polyline>()
    private val historyLabels = mutableListOf<Text>()

    fun onMapReady() {
        if (history != null) { drawHistory(); return }
        redraw?.invoke()
        updatePosition(userPosition)
    }

    fun clear() {
        job?.cancel()
        key = null
        sitePosition = null
        redraw = null
        linkedState = null
        linkedSnapshot = null
        history = null
        historyCurrent = null
        historyPosition = null
        clearHistoryLinks()
        lte.clear()
        nr.clear()
        line?.remove(); line = null
        distanceLabel?.remove(); distanceLabel = null
    }

    fun setDisplay(antennas: Boolean, link: Boolean) {
        lte.setAntennaVisible(antennas)
        nr.setAntennaVisible(antennas)
        showLink = link
        historyLines.forEach { it.isVisible = link }
        historyLabels.forEach { it.isVisible = link }
        updatePosition(userPosition)
    }

    fun update(state: NetworkPanelUiState?) {
        val rat = state?.cellType?.uppercase(Locale.ROOT).orEmpty()
        if (state == null || rat !in listOf("LTE", "NR")) {
            clear()
            onStatus(R.string.map_serving_no_match, null)
            return
        }
        val next = "${state.subId}|$rat|${state.ci}|${state.tac}|${state.arfcn}|${state.pci}"
        if (next == key && (sitePosition != null || job?.isActive == true || SystemClock.elapsedRealtime() - queryAt < 15_000)) return
        clear()
        key = next
        queryAt = SystemClock.elapsedRealtime()
        onStatus(R.string.map_serving_querying, null)
        job = scope.launch {
            try {
                if (rat == "LTE") {
                    val tac = state.tac.trim().toIntOrNull()
                    val frequency = state.arfcn.trim().toIntOrNull()
                    if (tac == null || frequency == null) {
                        onStatus(R.string.map_serving_no_match, null)
                        return@launch
                    }
                    val request = LteCellQueryBody(tac = tac, earfcn = frequency,
                        eci = state.ci.trim().toLongOrNull()?.takeIf { it > 0 }, pci = state.pci.trim().toIntOrNull())
                    val result = lookupServingStation(
                        local = { databaseOrNull { local.queryLte(request) }?.stationRows().orEmpty() },
                        site = {
                            val response = api.queryLteSite(request).requiredBody()
                            databaseOrNull { local.saveLteSiteQueryResp(response) }
                            response.stationRows()
                        },
                        cell = {
                            val response = api.queryLteCellParams(request).requiredBody()
                            databaseOrNull { local.saveLteQueryResp(response) }
                            response.stationRows()
                        },
                        match = { matchServingLte(state, it) }, candidates = { servingLteCandidates(state, it) },
                        hasPosition = { validStationCoordinates(it.latitude, it.longitude) })
                    ensureActive()
                    if (key != next) return@launch
                    val anchor = result.anchor
                    if (anchor != null) {
                        val rows = result.rows.map { row -> row.copy(latitude = row.latitude ?: anchor.latitude,
                            longitude = row.longitude ?: anchor.longitude) }.filter { row ->
                                (anchor.enodeb_id == null || row.enodeb_id == anchor.enodeb_id) &&
                                driveDistanceMeters(anchor.latitude!!, anchor.longitude!!, row.latitude!!, row.longitude!!) < 150
                        }
                        linkedState = state
                        linkedSnapshot = DriveStationSnapshot(rat = rat, latitude = anchor.latitude!!,
                            longitude = anchor.longitude!!, name = anchor.cell_name, sectors = rows.map(DriveStationSector::from))
                        redraw = { lte.renderNearbyCells(rows, linkedRows = listOf(anchor)) }
                        redraw?.invoke()
                        setSite(anchor.latitude!!, anchor.longitude!!, anchor.cell_name, rat)
                    } else onStatus(if (result.matchedWithoutPosition) R.string.map_serving_no_coordinates else R.string.map_serving_no_match, null)
                } else {
                    val request = NrQueryReq(gcellId = state.ci.trim().takeIf { it.toLongOrNull()?.let { id -> id > 0 } == true },
                        nrTac = state.tac.trim().toIntOrNull(), nrArfcn = state.arfcn.trim().toIntOrNull(), nrPci = state.pci.trim().toIntOrNull())
                    val result = lookupServingStation(
                        local = { databaseOrNull { local.queryNr(request) }?.data.orEmpty() },
                        site = {
                            val response = api.queryNrSite(request).requiredBody()
                            databaseOrNull { local.saveNrSiteQueryResp(response) }
                            response.stationRows()
                        },
                        cell = {
                            val response = api.queryNrCellParams(request).requiredBody()
                            databaseOrNull { local.saveNrQueryResp(response) }
                            response.data
                        },
                        match = { matchServingNr(state, it) }, candidates = { servingNrCandidates(state, it) },
                        hasPosition = { validStationCoordinates(it.latitude, it.longitude) })
                    ensureActive()
                    if (key != next) return@launch
                    val anchor = result.anchor
                    if (anchor != null) {
                        val rows = result.rows.map { row -> row.copy(latitude = row.latitude ?: anchor.latitude,
                            longitude = row.longitude ?: anchor.longitude) }.filter { row ->
                            driveDistanceMeters(anchor.latitude!!, anchor.longitude!!, row.latitude!!, row.longitude!!) < 150 }
                        linkedState = state
                        linkedSnapshot = DriveStationSnapshot(rat = rat, latitude = anchor.latitude!!,
                            longitude = anchor.longitude!!, name = anchor.cell_name, sectors = rows.map(DriveStationSector::from))
                        redraw = { nr.renderNearbyCells(rows, linkedRows = listOf(anchor)) }
                        redraw?.invoke()
                        setSite(anchor.latitude!!, anchor.longitude!!, anchor.cell_name, rat)
                    } else onStatus(if (result.matchedWithoutPosition) R.string.map_serving_no_coordinates else R.string.map_serving_no_match, null)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (key == next) onStatus(R.string.map_serving_failed, null) }
        }
    }

    private suspend fun <T> databaseOrNull(block: suspend () -> T): T? = try {
        withContext(Dispatchers.IO) { block() }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { null }

    private fun <T> Response<T>.requiredBody(): T {
        if (!isSuccessful) throw HttpException(this)
        return body() ?: error("Empty serving-cell response")
    }

    private fun setSite(lat: Double, lng: Double, name: String?, rat: String) {
        val (latitude, longitude) = Gcj02.wgs84ToGcj02(lat, lng)
        sitePosition = LatLng(latitude, longitude)
        onStatus(R.string.map_serving_name, "$rat · ${name?.takeIf { it.isNotBlank() } ?: "—"}")
        updatePosition(userPosition)
    }

    fun updatePosition(position: LatLng?) {
        if (history != null) return
        userPosition = position
        val site = sitePosition
        val visible = showLink && position != null && site != null
        line?.isVisible = visible
        distanceLabel?.isVisible = visible
        if (!visible) return
        if (line == null) line = map.addPolyline(PolylineOptions().add(position, site).color(Color.RED).width(5f).zIndex(45f))
        else line?.points = listOf(position, site)
        val distance = driveDistanceMeters(position.latitude, position.longitude, site.latitude, site.longitude)
        val label = if (distance < 1000) String.format(Locale.getDefault(), "%.0f m", distance)
            else String.format(Locale.getDefault(), "%.2f km", distance / 1000)
        val middle = LatLng((position.latitude + site.latitude) / 2, (position.longitude + site.longitude) / 2)
        if (distanceLabel == null) distanceLabel = map.addText(TextOptions().position(middle).text(label)
            .fontColor(Color.RED).backgroundColor(0xEFFFFFFF.toInt()).fontSize(30).zIndex(46f))
        else { distanceLabel?.position = middle; distanceLabel?.text = label }
        val state = linkedState
        val snapshot = linkedSnapshot
        if (state != null && snapshot != null) onLinked(state, snapshot, position)
    }

    fun showHistory(stations: List<DriveStationEntity>, simSlot: Int, point: DrivePoint?) {
        clear()
        history = driveRecordedStations(stations, simSlot)
        historyCurrent = driveHistoryServingStation(history!!, point?.let { DriveStyle.cellKey(it.snapshot) })
        historyPosition = point?.latLng
        drawHistory()
        onStatus(R.string.map_history_stations, history!!.size.toString())
    }

    fun selectHistoryPoint(point: DrivePoint) {
        val saved = history ?: return
        historyCurrent = driveHistoryServingStation(saved, DriveStyle.cellKey(point.snapshot))
        historyPosition = point.latLng
        drawHistory()
    }

    private fun clearHistoryLinks() {
        historyLines.forEach { it.remove() }; historyLines.clear()
        historyLabels.forEach { it.remove() }; historyLabels.clear()
    }

    private fun drawHistory() {
        val saved = history ?: return
        clearHistoryLinks()
        val current = historyCurrent
        lte.renderNearbyCells(saved.filter { it.second.rat == "LTE" }.flatMap { it.second.sectors }.map { it.lte() }.distinct(),
            current?.second?.takeIf { it.rat == "LTE" }?.sectors.orEmpty().map { it.lte() },
            linkedRows = current?.second?.takeIf { it.rat == "LTE" }?.sectors.orEmpty().map { it.lte() })
        nr.renderNearbyCells(saved.filter { it.second.rat == "NR" }.flatMap { it.second.sectors }.map { it.nr() }.distinct(),
            current?.second?.takeIf { it.rat == "NR" }?.sectors.orEmpty().map { it.nr() },
            linkedRows = current?.second?.takeIf { it.rat == "NR" }?.sectors.orEmpty().map { it.nr() })
        current?.let { (row, snapshot) ->
            val (lat, lng) = Gcj02.wgs84ToGcj02(snapshot.latitude, snapshot.longitude)
            val site = LatLng(lat, lng)
            val user = historyPosition ?: LatLng(row.userLat, row.userLng)
            historyLines.add(map.addPolyline(PolylineOptions().add(user, site).color(Color.RED).width(5f).zIndex(45f).visible(showLink)))
            val distance = driveDistanceMeters(user.latitude, user.longitude, lat, lng)
            val label = String.format(Locale.getDefault(), if (distance < 1000) "%.0f m" else "%.2f km",
                if (distance < 1000) distance else distance / 1000)
            historyLabels.add(map.addText(TextOptions().position(LatLng((user.latitude + lat) / 2, (user.longitude + lng) / 2))
                .text(label).fontColor(Color.RED).backgroundColor(0xEFFFFFFF.toInt()).fontSize(30).zIndex(46f).visible(showLink)))
        }
    }
}

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

internal data class HistoryPointStationInfo(val name: String? = null, val distanceMeters: Double? = null,
    val messageRes: Int? = null)

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
    private var nearDistanceLabel: Text? = null
    private var showLink = true
    private var exclusion = StationExclusion()
    private var redraw: (() -> Unit)? = null
    private var linkedState: NetworkPanelUiState? = null
    private var linkedSnapshot: DriveStationSnapshot? = null
    private var lastState: NetworkPanelUiState? = null
    private var waitingForPosition = false
    private var history: List<Pair<DriveStationEntity, DriveStationSnapshot>>? = null
    private var historyCurrent: Pair<DriveStationEntity, DriveStationSnapshot>? = null
    private var historyPosition: LatLng? = null
    private var historyPointState: NetworkPanelUiState? = null
    private val historyLines = mutableListOf<Polyline>()
    private val historyLabels = mutableListOf<Text>()

    fun onMapReady() {
        if (history != null) { drawHistory(); return }
        redraw?.invoke()
        updatePosition(userPosition)
    }

    fun clear() {
        job?.cancel()
        job = null
        key = null
        sitePosition = null
        redraw = null
        linkedState = null
        linkedSnapshot = null
        lastState = null
        waitingForPosition = false
        history = null
        historyCurrent = null
        historyPosition = null
        historyPointState = null
        clearHistoryLinks()
        lte.clear()
        nr.clear()
        line?.remove(); line = null
        distanceLabel?.remove(); distanceLabel = null
        nearDistanceLabel?.remove(); nearDistanceLabel = null
    }

    fun setDisplay(antennas: Boolean, link: Boolean) {
        lte.setAntennaVisible(antennas)
        nr.setAntennaVisible(antennas)
        showLink = link
        historyLines.forEach { it.isVisible = link }
        historyLabels.forEach { it.isVisible = link }
        updatePosition(userPosition)
        onCameraChanged()
    }

    fun setExclusion(value: StationExclusion) {
        if (exclusion == value) return
        exclusion = value
        if (history != null) {
            drawHistory()
            updateHistoryPointStatus()
        } else {
            redraw?.invoke()
            updatePosition(userPosition)
            updateSiteStatus()
        }
    }

    private fun hidden(snapshot: DriveStationSnapshot, state: NetworkPanelUiState?) = exclusion.excludes(snapshot, state)

    private fun currentReference() = servingStationReference(userPosition?.latitude, userPosition?.longitude)

    private fun visibleHistoryCurrent() = historyCurrent?.takeUnless { (row, snapshot) ->
        val position = historyPosition ?: LatLng(row.userLat, row.userLng)
        hidden(snapshot, historyPointState) || !servingStationInRange(snapshot.latitude, snapshot.longitude,
            servingStationReference(position.latitude, position.longitude))
    }

    private fun showLookupFailure(result: ServingStationLookup<*>, movedOutOfRange: Boolean = false) {
        onStatus(when {
            result.rejectedByDistance || movedOutOfRange -> R.string.map_serving_too_far
            result.matchedWithoutPosition -> R.string.map_serving_no_coordinates
            else -> R.string.map_serving_no_match
        }, null)
    }

    private fun updateSiteStatus() {
        val snapshot = linkedSnapshot ?: return
        if (hidden(snapshot, linkedState)) onStatus(R.string.map_station_exclusion_serving, null)
        else onStatus(R.string.map_serving_name, "${snapshot.rat} · ${snapshot.name?.takeIf { it.isNotBlank() } ?: "—"}")
    }

    fun update(state: NetworkPanelUiState?) {
        val rat = state?.cellType?.uppercase(Locale.ROOT).orEmpty()
        if (state == null || rat !in listOf("LTE", "NR")) {
            clear()
            onStatus(R.string.map_serving_no_match, null)
            return
        }
        val next = "${state.subId}|$rat|${state.ci}|${state.tac}|${state.arfcn}|${state.pci}"
        if (next == key && !waitingForPosition &&
            (sitePosition != null || job?.isActive == true || SystemClock.elapsedRealtime() - queryAt < 15_000)) return
        clear()
        lastState = state
        key = next
        if (currentReference() == null) {
            waitingForPosition = true
            onStatus(R.string.map_serving_location_pending, null)
            return
        }
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
                        match = { matchServingLte(state, it, currentReference()) }, candidates = { servingLteCandidates(state, it) },
                        hasPosition = { validStationCoordinates(it.latitude, it.longitude) },
                        isWithinRange = { servingStationInRange(it.latitude, it.longitude, currentReference()) })
                    ensureActive()
                    if (key != next) return@launch
                    val anchor = result.anchor
                    if (anchor != null && servingStationInRange(anchor.latitude, anchor.longitude, currentReference())) {
                        val rows = result.rows.map { row -> row.copy(latitude = row.latitude ?: anchor.latitude,
                            longitude = row.longitude ?: anchor.longitude) }.filter { row ->
                                (anchor.enodeb_id == null || row.enodeb_id == anchor.enodeb_id) &&
                                driveDistanceMeters(anchor.latitude!!, anchor.longitude!!, row.latitude!!, row.longitude!!) < 150
                        }
                        linkedState = state
                        linkedSnapshot = DriveStationSnapshot(rat = rat, latitude = anchor.latitude!!,
                            longitude = anchor.longitude!!, name = anchor.cell_name, sectors = rows.map(DriveStationSector::from))
                        redraw = { lte.renderNearbyCells(rows.filterNot { exclusion.excludes(it) },
                            linkedRows = listOf(anchor).filterNot { exclusion.excludes(it) || exclusion.excludes(state) }) }
                        redraw?.invoke()
                        setSite(anchor.latitude!!, anchor.longitude!!)
                    } else showLookupFailure(result, movedOutOfRange = anchor != null)
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
                        match = { matchServingNr(state, it, currentReference()) }, candidates = { servingNrCandidates(state, it) },
                        hasPosition = { validStationCoordinates(it.latitude, it.longitude) },
                        isWithinRange = { servingStationInRange(it.latitude, it.longitude, currentReference()) })
                    ensureActive()
                    if (key != next) return@launch
                    val anchor = result.anchor
                    if (anchor != null && servingStationInRange(anchor.latitude, anchor.longitude, currentReference())) {
                        val rows = result.rows.map { row -> row.copy(latitude = row.latitude ?: anchor.latitude,
                            longitude = row.longitude ?: anchor.longitude) }.filter { row ->
                            driveDistanceMeters(anchor.latitude!!, anchor.longitude!!, row.latitude!!, row.longitude!!) < 150 }
                        linkedState = state
                        linkedSnapshot = DriveStationSnapshot(rat = rat, latitude = anchor.latitude!!,
                            longitude = anchor.longitude!!, name = anchor.cell_name, sectors = rows.map(DriveStationSector::from))
                        redraw = { nr.renderNearbyCells(rows, linkedRows = listOf(anchor)) }
                        redraw?.invoke()
                        setSite(anchor.latitude!!, anchor.longitude!!)
                    } else showLookupFailure(result, movedOutOfRange = anchor != null)
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

    private fun setSite(lat: Double, lng: Double) {
        val (latitude, longitude) = Gcj02.wgs84ToGcj02(lat, lng)
        sitePosition = LatLng(latitude, longitude)
        updateSiteStatus()
        updatePosition(userPosition)
    }

    fun updatePosition(position: LatLng?) {
        if (history != null) return
        userPosition = position
        val reference = currentReference()
        if (reference == null) {
            val state = lastState
            if (state != null && !waitingForPosition) {
                clear()
                lastState = state
                waitingForPosition = true
                onStatus(R.string.map_serving_location_pending, null)
            }
            return
        }
        if (waitingForPosition) {
            lastState?.let(::update)
            return
        }
        linkedSnapshot?.let { snapshot ->
            if (!servingStationInRange(snapshot.latitude, snapshot.longitude, reference)) {
                val state = lastState
                clear()
                state?.let(::update)
                return
            }
        }
        val site = sitePosition
        val state = linkedState
        val snapshot = linkedSnapshot
        // Display exclusions must not discard the original recorded station snapshot.
        if (state != null && snapshot != null && position != null) onLinked(state, snapshot, position)
        val visible = showLink && position != null && site != null &&
            (snapshot == null || !hidden(snapshot, state))
        line?.isVisible = visible
        distanceLabel?.isVisible = visible
        if (!visible) nearDistanceLabel?.isVisible = false
        if (!visible) return
        if (line == null) line = map.addPolyline(PolylineOptions().add(position, site).color(Color.RED).width(5f).zIndex(45f))
        else line?.points = listOf(position, site)
        val distance = if (snapshot != null) driveDistanceMeters(reference.latitude, reference.longitude,
            snapshot.latitude, snapshot.longitude) else driveDistanceMeters(position.latitude, position.longitude, site.latitude, site.longitude)
        val label = if (distance < 1000) String.format(Locale.getDefault(), "%.0f m", distance)
            else String.format(Locale.getDefault(), "%.2f km", distance / 1000)
        val middle = LatLng((position.latitude + site.latitude) / 2, (position.longitude + site.longitude) / 2)
        if (distanceLabel == null) distanceLabel = map.addText(TextOptions().position(middle).text(label)
            .fontColor(Color.RED).backgroundColor(0xEFFFFFFF.toInt()).fontSize(30).zIndex(46f))
        else { distanceLabel?.position = middle; distanceLabel?.text = label }
        updateNearLabel(position, site, label)
    }

    fun showHistory(stations: List<DriveStationEntity>, simSlot: Int, point: DrivePoint?) {
        clear()
        history = driveRecordedStations(stations, simSlot)
        historyCurrent = driveHistoryStationForPoint(history!!, point)
        historyPosition = point?.latLng
        historyPointState = point?.snapshot
        drawHistory()
        updateHistoryPointStatus()
    }

    fun selectHistoryPoint(point: DrivePoint): HistoryPointStationInfo {
        val saved = history ?: return HistoryPointStationInfo(messageRes = R.string.map_history_point_station_missing)
        historyCurrent = driveHistoryStationForPoint(saved, point)
        historyPosition = point.latLng
        historyPointState = point.snapshot
        drawHistory()
        return updateHistoryPointStatus()
    }

    private fun updateHistoryPointStatus(): HistoryPointStationInfo {
        val selected = historyCurrent
        val position = historyPosition
        val info = when {
            selected == null || position == null -> HistoryPointStationInfo(messageRes = R.string.map_history_point_station_missing)
            !servingStationInRange(selected.second.latitude, selected.second.longitude,
                servingStationReference(position.latitude, position.longitude)) ->
                HistoryPointStationInfo(messageRes = R.string.map_serving_too_far)
            hidden(selected.second, historyPointState) -> HistoryPointStationInfo(messageRes = R.string.map_station_exclusion_serving)
            else -> {
                val reference = servingStationReference(position.latitude, position.longitude)!!
                HistoryPointStationInfo(name = "${selected.second.rat} · ${selected.second.name?.takeIf { it.isNotBlank() } ?: "—"}",
                    distanceMeters = driveDistanceMeters(reference.latitude, reference.longitude,
                        selected.second.latitude, selected.second.longitude))
            }
        }
        if (info.messageRes != null) onStatus(info.messageRes, null)
        else onStatus(R.string.map_serving_name, "${info.name} · ${formatDistance(info.distanceMeters!!)}")
        return info
    }

    private fun formatDistance(meters: Double) = if (meters < 1000) String.format(Locale.getDefault(), "%.0f m", meters)
        else String.format(Locale.getDefault(), "%.2f km", meters / 1000)

    private fun clearHistoryLinks() {
        historyLines.forEach { it.remove() }; historyLines.clear()
        historyLabels.forEach { it.remove() }; historyLabels.clear()
        nearDistanceLabel?.remove(); nearDistanceLabel = null
    }

    /** Keep a second distance label close to the user even when the far site is off screen. */
    private fun updateNearLabel(user: LatLng, site: LatLng, label: String) {
        val projection = map.projection
        val from = projection.toScreenLocation(user)
        val to = projection.toScreenLocation(site)
        val pixels = kotlin.math.hypot((to.x.toDouble() - from.x), (to.y.toDouble() - from.y))
        val density = android.content.res.Resources.getSystem().displayMetrics.density
        if (!showLink || !pixels.isFinite() || pixels < 180 * density) {
            nearDistanceLabel?.isVisible = false
            return
        }
        val fraction = 64 * density / pixels
        val position = projection.fromScreenLocation(android.graphics.Point(
            (from.x + (to.x.toDouble() - from.x) * fraction).toInt(),
            (from.y + (to.y.toDouble() - from.y) * fraction).toInt()))
        if (nearDistanceLabel == null) nearDistanceLabel = map.addText(TextOptions().position(position).text(label)
            .fontColor(Color.RED).backgroundColor(0xEFFFFFFF.toInt()).fontSize(30).zIndex(47f))
        else { nearDistanceLabel?.position = position; nearDistanceLabel?.text = label; nearDistanceLabel?.isVisible = true }
    }

    fun onCameraChanged() {
        val current = visibleHistoryCurrent()
        val user = if (history != null) historyPosition ?: current?.first?.let { LatLng(it.userLat, it.userLng) } else userPosition
        val site = if (history != null) current?.second?.let {
            val p = Gcj02.wgs84ToGcj02(it.latitude, it.longitude); LatLng(p.first, p.second)
        } else sitePosition?.takeUnless { linkedSnapshot?.let { hidden(it, linkedState) } == true }
        if (user == null || site == null || !showLink) { nearDistanceLabel?.isVisible = false; return }
        val snapshot = if (history != null) current?.second else linkedSnapshot
        val reference = servingStationReference(user.latitude, user.longitude)
        val meters = if (snapshot != null && reference != null) driveDistanceMeters(reference.latitude, reference.longitude,
            snapshot.latitude, snapshot.longitude) else driveDistanceMeters(user.latitude, user.longitude, site.latitude, site.longitude)
        val label = if (meters < 1000) String.format(Locale.getDefault(), "%.0f m", meters)
            else String.format(Locale.getDefault(), "%.2f km", meters / 1000)
        updateNearLabel(user, site, label)
    }

    private fun drawHistory() {
        val saved = history ?: return
        clearHistoryLinks()
        val current = visibleHistoryCurrent()
        lte.renderNearbyCells(saved.filter { it.second.rat == "LTE" }.flatMap { it.second.sectors }.map { it.lte() }
            .filterNot { exclusion.excludes(it) }.distinct(),
            current?.second?.takeIf { it.rat == "LTE" }?.sectors.orEmpty().map { it.lte() }.filterNot { exclusion.excludes(it) },
            linkedRows = current?.second?.takeIf { it.rat == "LTE" }?.sectors.orEmpty().map { it.lte() }.filterNot { exclusion.excludes(it) })
        nr.renderNearbyCells(saved.filter { it.second.rat == "NR" }.flatMap { it.second.sectors }.map { it.nr() }.distinct(),
            current?.second?.takeIf { it.rat == "NR" }?.sectors.orEmpty().map { it.nr() },
            linkedRows = current?.second?.takeIf { it.rat == "NR" }?.sectors.orEmpty().map { it.nr() })
        current?.let { (row, snapshot) ->
            val (lat, lng) = Gcj02.wgs84ToGcj02(snapshot.latitude, snapshot.longitude)
            val site = LatLng(lat, lng)
            val user = historyPosition ?: LatLng(row.userLat, row.userLng)
            historyLines.add(map.addPolyline(PolylineOptions().add(user, site).color(Color.RED).width(5f).zIndex(45f).visible(showLink)))
            val reference = servingStationReference(user.latitude, user.longitude)!!
            val distance = driveDistanceMeters(reference.latitude, reference.longitude, snapshot.latitude, snapshot.longitude)
            val label = String.format(Locale.getDefault(), if (distance < 1000) "%.0f m" else "%.2f km",
                if (distance < 1000) distance else distance / 1000)
            historyLabels.add(map.addText(TextOptions().position(LatLng((user.latitude + lat) / 2, (user.longitude + lng) / 2))
                .text(label).fontColor(Color.RED).backgroundColor(0xEFFFFFFF.toInt()).fontSize(30).zIndex(46f).visible(showLink)))
            updateNearLabel(user, site, label)
        }
    }
}

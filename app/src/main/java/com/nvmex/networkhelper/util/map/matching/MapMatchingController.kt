package com.nvmex.networkhelper.util.map.matching

import android.content.SharedPreferences
import android.content.res.Configuration
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.amap.api.maps.AMap
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.Polyline
import com.amap.api.maps.model.PolylineOptions
import com.google.gson.JsonParser
import com.nvmex.networkhelper.R
import com.nvmex.networkhelper.model.map.DrivePoint
import com.nvmex.networkhelper.ui.settings.AppSettingsStore
import com.nvmex.networkhelper.util.map.Gcj02
import kotlinx.coroutines.*

/** Cloud paths are separate; a delayed endpoint must never replace a newer GNSS/IMU sample. */
class MapMatchingController(private val activity: AppCompatActivity, private val map: AMap,
    private val scope: CoroutineScope, parent: LinearLayout, private val output: (MatchResult?, String?) -> Unit) {
    private var worker: Job? = null
    private var historyWorker: Job? = null
    private var session: FalconSdkSession? = null
    private var epoch = 0L
    private var active = false
    private var closed = false
    private var lastFix: MatchFix? = null
    private var trace: FalconTrace? = null
    private var historyKey: String? = null
    private var historyPoints: List<DrivePoint> = emptyList()
    private val lines = mutableListOf<Polyline>()
    private var pathVisible = true
    private var explanation = activity.getString(R.string.map_falcon_idle_description)
    var enabled = false
        private set
    private val status = TextView(activity).apply {
        textSize = 10f
        val dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        setTextColor(if (dark) 0xFF65D7E8.toInt() else 0xFF167C99.toInt())
        gravity = Gravity.CENTER_VERTICAL
        setPadding(12, 0, 4, 0)
        setSingleLine()
        maxWidth = (resources.displayMetrics.density * 116).toInt()
        ellipsize = TextUtils.TruncateAt.END
        visibility = View.GONE
        isFocusable = true
        setOnClickListener { details() }
    }
    private val prefs = AppSettingsStore.preferences(activity)
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == AppSettingsStore.KEY_MAP_MATCHING) activity.runOnUiThread {
            configure(prefs.getBoolean(AppSettingsStore.KEY_MAP_MATCHING, false))
        }
    }
    init {
        parent.addView(status, LinearLayout.LayoutParams(-2, -1))
        prefs.registerOnSharedPreferenceChangeListener(listener)
        configure(prefs.getBoolean(AppSettingsStore.KEY_MAP_MATCHING, false))
    }
    fun setEnabled(value: Boolean) {
        AppSettingsStore.write(activity, AppSettingsStore.current(activity).copy(enableMapMatching = value))
        configure(value)
    }
    private fun configure(value: Boolean) {
        if (enabled == value && (worker != null || !value || !active)) return
        enabled = value
        stopWorker()
        clearDrawing()
        output(null, null)
        status.visibility = if (value) View.VISIBLE else View.GONE
        if (value) {
            setStatus(R.string.map_falcon_idle, R.string.map_falcon_idle_description)
            if (active) startWorker()
            else if (historyPoints.isNotEmpty()) showHistory(historyPoints)
        }
    }
    fun setActive(value: Boolean) {
        if (closed || active == value) return
        active = value
        stopWorker()
        output(null, null)
        if (!value) {
            clearDrawing()
            if (enabled) setStatus(R.string.map_falcon_idle, R.string.map_falcon_idle_description)
        }
        if (value && enabled) {
            historyKey = null
            historyPoints = emptyList()
            clearDrawing()
            startWorker()
        }
    }
    private fun startWorker() {
        if (closed || !enabled || !active) return
        val generation = ++epoch
        worker = scope.launch {
            while (isActive && epoch == generation) {
                val sdk = FalconSdkSession(activity.applicationContext)
                session = sdk
                if (sdk.sid <= 0) {
                    setStatus(R.string.map_falcon_unavailable, R.string.map_falcon_not_configured)
                    break
                }
                try {
                    setStatus(R.string.map_falcon_connecting, R.string.map_falcon_connecting)
                    sdk.start()
                    var failures = 0
                    while (isActive && epoch == generation) {
                        try {
                            val result = sdk.query()
                            ensureActive()
                            if (generation != epoch) break
                            trace = result
                            draw(result)
                            failures = 0
                            setStatus(if (result.segments.isEmpty()) R.string.map_falcon_waiting else R.string.map_falcon_ready,
                                if (result.segments.isEmpty()) R.string.map_falcon_waiting else R.string.map_falcon_description)
                            lastFix?.let(::publish)
                        } catch (cancel: CancellationException) { throw cancel }
                        catch (error: Exception) { failures++; setError(error) }
                        delay(if (failures == 0) 15_000 else (15_000L * failures).coerceAtMost(60_000))
                    }
                } catch (cancel: CancellationException) { throw cancel }
                catch (error: Exception) { setError(error) }
                finally { sdk.stop(); if (session === sdk) session = null }
                delay(30_000)
            }
        }
    }
    fun accept(fix: MatchFix) {
        if (!enabled || !active) return
        lastFix = fix
        publish(fix)
    }
    private fun publish(fix: MatchFix) {
        val current = trace ?: session?.takeIf { it.trid > 0 }?.let { FalconTrace(it.sid, it.tid, it.trid, emptyList()) }
        val gcj = Gcj02.wgs84ToGcj02(fix.position.lat, fix.position.lng)
        val point = if (fix.source == MatchSource.IMU) null else current?.correctionFor(fix.timestamp, RoutePosition(gcj.first, gcj.second), fix.accuracy)
        val corrected = point?.let { Gcj02.gcj02ToWgs84(it.lat, it.lng) }?.let { RoutePosition(it.first, it.second) }
        val result = MatchResult(fix, corrected, if (corrected != null) RouteType.ROAD else RouteType.UNKNOWN,
            routeId = current?.trid?.toString().orEmpty(), status = explanation, degraded = fix.source == MatchSource.IMU)
        val metadata = current?.metadata(fix)?.let { json ->
            JsonParser.parseString(json).asJsonObject.apply {
                addProperty("status", explanation)
                addProperty("offset_m", result.offsetMeters)
                addProperty("degraded", result.degraded)
                if (corrected != null) {
                    addProperty("type", "ROAD")
                    addProperty("source", "AMAP_FALCON")
                    addProperty("matched_wgs84_lat", corrected.lat); addProperty("matched_wgs84_lng", corrected.lng)
                }
            }.toString()
        }
        output(result, metadata)
    }
    fun qualityLost() { lastFix = null; output(null, null) }

    /** Replay queries existing tracks; it never starts gathering or uploads historical samples. */
    fun showHistory(points: List<DrivePoint>) {
        historyPoints = points
        if (!enabled) return
        val requests = falconHistorySlices(points.map { it.ts to it.mapMatchJson }, System.currentTimeMillis())
        if (requests.isEmpty()) {
            historyWorker?.cancel(); historyWorker = null; historyKey = null
            clearDrawing()
            setStatus(R.string.map_falcon_waiting, R.string.map_falcon_history_no_data)
            return
        }
        val key = requests.joinToString()
        if (historyKey == key) return
        historyKey = key
        historyWorker?.cancel()
        clearDrawing()
        val generation = epoch
        historyWorker = scope.launch {
            val sdk = FalconSdkSession(activity.applicationContext)
            try {
                setStatus(R.string.map_falcon_connecting, R.string.map_falcon_connecting)
                val segments = requests.flatMap { request ->
                    sdk.query(request.start, request.end, request.sid, request.tid, request.trid, maxPages = 100).segments
                }
                ensureActive()
                if (generation == epoch && !active) {
                    val request = requests.first()
                    draw(FalconTrace(request.sid, request.tid, request.trid, segments))
                    setStatus(R.string.map_falcon_history, R.string.map_falcon_history_description)
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { setError(error) }
            finally { sdk.stop() }
        }
    }
    /** Cloud tracks have no SIM/band labels and cannot represent a band-filtered point stream. */
    fun setPathVisible(visible: Boolean) {
        pathVisible = visible
        lines.forEach { it.isVisible = visible }
    }

    private fun draw(result: FalconTrace) {
        val paths = result.segments.filter { it.size >= 2 }
        paths.forEachIndexed { index, points ->
            val options = PolylineOptions().addAll(points.map { LatLng(it.lat, it.lng) })
                .color(0xDD00A8B5.toInt()).width(7f).zIndex(18f).visible(pathVisible)
            if (index < lines.size) lines[index].setOptions(options) else lines.add(map.addPolyline(options))
        }
        while (lines.size > paths.size) lines.removeAt(lines.lastIndex).remove()
    }
    private fun setStatus(label: Int, detail: Int) {
        status.setText(label)
        explanation = activity.getString(detail)
        status.contentDescription = explanation
    }
    private fun setError(error: Exception) {
        status.setText(R.string.map_falcon_retrying)
        explanation = if (error is FalconException) activity.getString(R.string.map_falcon_error_code, error.code)
            else activity.getString(R.string.map_falcon_network_error)
        status.contentDescription = explanation
    }
    private fun details() {
        AlertDialog.Builder(activity).setTitle(R.string.map_matching_title)
            .setMessage("$explanation\n\n${activity.getString(R.string.map_falcon_description)}")
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.map_falcon_retry) { _, _ ->
                stopWorker(); historyKey = null
                if (enabled && active) startWorker()
                else if (enabled && historyPoints.isNotEmpty()) showHistory(historyPoints)
                else if (enabled) setStatus(R.string.map_falcon_idle, R.string.map_falcon_idle_description)
            }.show()
    }
    private fun clearDrawing() { lines.forEach { runCatching { it.remove() } }; lines.clear() }
    private fun stopWorker() {
        epoch++
        historyKey = null
        worker?.cancel(); worker = null
        historyWorker?.cancel(); historyWorker = null
        session?.stop(); session = null
        trace = null; lastFix = null
    }
    fun close() { closed = true; stopWorker(); prefs.unregisterOnSharedPreferenceChangeListener(listener); clearDrawing() }
}

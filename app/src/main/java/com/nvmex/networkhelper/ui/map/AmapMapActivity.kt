package com.nvmex.networkhelper.ui.map

import LteCellOverlayManager
import NrCellOverlayManager
import ServerCoord
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import com.amap.api.location.AMapLocationClient
import com.amap.api.location.AMapLocation
import com.amap.api.location.AMapLocationClientOption
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.LocationSource
import com.amap.api.maps.MapView
import com.amap.api.maps.model.BitmapDescriptorFactory
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.Marker
import com.amap.api.maps.model.MarkerOptions
import com.amap.api.maps.model.MyLocationStyle
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.switchmaterial.SwitchMaterial
import com.nvmex.networkhelper.R
import com.nvmex.networkhelper.model.map.DrivePoint
import com.nvmex.networkhelper.model.network.NetworkPanelUiState
import com.nvmex.networkhelper.network.api.ApiService
import com.nvmex.networkhelper.network.model.NearbyCellsRequest
import com.nvmex.networkhelper.network.model.NearbyCellsResponse
import com.nvmex.networkhelper.repository.CellParamLocalRepository
import com.nvmex.networkhelper.ui.settings.AppLanguageManager
import com.nvmex.networkhelper.util.map.AppDb
import com.nvmex.networkhelper.util.map.DrivePointDao
import com.nvmex.networkhelper.util.map.toDriveEntity
import com.nvmex.networkhelper.util.map.toDrivePointOrNull
import com.nvmex.networkhelper.util.map.DriveSampler
import com.nvmex.networkhelper.util.map.DriveHistory
import com.nvmex.networkhelper.util.map.DriveKeepAliveService
import com.nvmex.networkhelper.util.map.Gcj02
import com.nvmex.networkhelper.util.map.SnapshotJson
import com.nvmex.networkhelper.util.network.NetworkPanelRepositoryMultiSim
import com.nvmex.networkhelper.util.network.TelephonySnapshotterMultiSim
import com.nvmex.networkhelper.util.map.DeadReckoningEngine
import com.nvmex.networkhelper.util.map.DriveMotionTracker
import com.nvmex.networkhelper.util.map.DrivePersistence
import com.nvmex.networkhelper.util.map.DriveSessionEntity
import com.nvmex.networkhelper.util.map.DriveTrackFix
import com.nvmex.networkhelper.util.map.ServingStationOverlay
import com.nvmex.networkhelper.util.map.driveDistanceMeters
import com.nvmex.networkhelper.util.map.driveTrackLength
import com.nvmex.networkhelper.util.map.driveSnapshotsBySlot
import com.nvmex.networkhelper.util.map.driveFixElapsedMillis
import com.nvmex.networkhelper.util.map.driveValidPosition
import com.nvmex.networkhelper.util.map.DrivePointWriteQueue
import com.nvmex.networkhelper.util.map.DriveStationEntity
import com.nvmex.networkhelper.util.map.DriveStationJson
import com.nvmex.networkhelper.util.map.DriveStationSnapshot
import com.nvmex.networkhelper.util.map.DriveStyle
import com.nvmex.networkhelper.util.map.DriveBandFilter
import com.nvmex.networkhelper.util.map.DriveCoverage
import com.nvmex.networkhelper.util.map.MapBubbleBackground
import com.nvmex.networkhelper.util.map.MarkerInfoWindowAdapter
import com.nvmex.networkhelper.util.map.StationDistancePreview
import com.nvmex.networkhelper.util.map.DriveCourse
import com.nvmex.networkhelper.util.map.DriveLocationMarker
import com.nvmex.networkhelper.util.map.StationExclusion
import com.nvmex.networkhelper.util.map.nearbyProbeBands
import com.nvmex.networkhelper.util.map.mergeNearbyProbeResults
import com.nvmex.networkhelper.util.map.ProbeAntennaSelection
import com.nvmex.networkhelper.util.map.ProbedStation
import com.nvmex.networkhelper.util.map.driveBandLabel
import com.nvmex.networkhelper.util.map.DrivePositionSelector
import com.nvmex.networkhelper.util.map.DriveSamplePosition
import com.nvmex.networkhelper.util.map.driveLog
import com.nvmex.networkhelper.util.map.matching.*
import android.text.TextUtils
import android.widget.PopupMenu
import android.graphics.drawable.GradientDrawable
import android.location.Location
import java.util.concurrent.ConcurrentHashMap
import com.nvmex.networkhelper.xposed.logger.Logger
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class AmapMapActivity : AppCompatActivity(), LocationSource {

    @Inject
    lateinit var apiService: ApiService

    @Inject
    lateinit var localRepository: CellParamLocalRepository

    private lateinit var mapView: MapView
    private lateinit var aMap: AMap

    private var amapListener: LocationSource.OnLocationChangedListener? = null
    private var locClient: AMapLocationClient? = null
    private var lastLatLng: LatLng? = null
    private var lastRawPosition: Triple<Double?, Double?, String?> = Triple(null, null, null)
    private var mapMatching: MapMatchingController? = null
    private var lastMapMatchJson: String? = null
    private var lastAmapFixAt = -1L
    private val samplePositions = DrivePositionSelector()
    private var lastAmapIsGps = false
    private var lastAmapSpeed: Double? = null
    private var isFirstFix = true

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var multiSnapshotter: TelephonySnapshotterMultiSim
    private lateinit var panelRepository: NetworkPanelRepositoryMultiSim
    private lateinit var sampler: DriveSampler

    // ===== 基站绘制（依赖 aMap + apiService，必须 onCreate 后初始化）=====
    private lateinit var lteOverlay: LteCellOverlayManager
    private lateinit var nrOverlay: NrCellOverlayManager
    private lateinit var nearbyLteOverlay: LteCellOverlayManager
    private lateinit var nearbyNrOverlay: NrCellOverlayManager
    private lateinit var probeAntennaLte: LteCellOverlayManager
    private lateinit var probeAntennaNr: NrCellOverlayManager
    private val probeAntennas = ProbeAntennaSelection()
    private var stationDistancePreview: StationDistancePreview? = null
    private var nearbyJob: Job? = null
    private var lastNearbyCenter: LatLng? = null
    private var lastNearbyAt = 0L
    private var nearbyEnabled = false
    private var nearbyRadiusKm = 3
    private var nearbyBand: String? = null
    private var nearbyRat: String? = null
    private var nearbyResult: NearbyCellsResponse? = null
    private var nearbyPartialFailure = false
    private var lastCameraOverlayRefreshAt = 0L
    private var cameraControlAnimating = false
    private var stationExclusion = StationExclusion()
    private var nearbyRangeCircle: com.amap.api.maps.model.Circle? = null

    private var cellWatchJob: kotlinx.coroutines.Job? = null
    private var mapResumed = false

    // ===== DB =====
    private lateinit var dao: DrivePointDao
    private val stationWrites = DrivePointWriteQueue<DriveStationEntity>(DrivePersistence.scope) { rows, _ -> dao.insertStations(rows) }
    private val correctionWrites = DrivePointWriteQueue<FalconPointUpdate>(DrivePersistence.scope) { rows, sid -> dao.applyFalconCorrections(sid, rows) }
    private val linkedStationKeys = mutableSetOf<String>()
    private var restoredStations = emptyList<DriveStationEntity>()
    private var currentSessionId: String? = null
    private var persistSessionId: String? = null
    @Volatile
    private var hasNewPointsInSession: Boolean = false

    // ===== driving =====
    private var isDriving = false
    private var finishingDrive = false
    private var bandFilter = DriveBandFilter()
    private var visibleHistory: DriveHistory? = null
    private var restoredHistory: DriveHistory? = null
        set(value) {
            field = value
            visibleHistory = value?.filteredBy(bandFilter)
            locationMarker?.setVisible(value == null && driveCourse.isMoving)
            if (::aMap.isInitialized) updateDirectionDisplay()
        }
    private var restoreJob: Job? = null
    private var lastToggleAt = 0L
    private var drivePermissionPending = false

    // ===== 视角跟随状态机 =====
    private var followMyLocation = false
    private var lastCenterClickAt = 0L
    private var ignoreCameraMoveCancelOnce = false

    // ===== 3D倾斜 =====
    private var is3DMode = false
    private var lastBearingDeg: Float? = null
    private val driveCourse = DriveCourse()
    private var sdkDirectionVisible: Boolean? = null
    private var locationMarker: DriveLocationMarker? = null
    private val followTilt3D = 55f

    // ===== UI =====
    private val density by lazy { resources.displayMetrics.density }
    private lateinit var fabCenter: FloatingActionButton
    private var fabDrive: FloatingActionButton? = null
    private lateinit var fabMore: FloatingActionButton
    private lateinit var signalPanel: LinearLayout
    private lateinit var nearbyStatus: TextView
    private lateinit var servingStatus: TextView
    private lateinit var mapTypeButton: TextView
    private lateinit var viewModeButton: TextView
    private lateinit var speedView: TextView
    private lateinit var fabActions: LinearLayout
    private lateinit var fabActionsHost: ScrollView
    private lateinit var mapStatusRow: LinearLayout
    private lateinit var bandCoveragePanel: LinearLayout
    private lateinit var bandCoverageTitle: TextView
    private lateinit var bandCoverageBands: TextView
    private lateinit var bandCoverageDistance: TextView
    private lateinit var bandCoverageNote: TextView
    private var bandCoverage = DriveCoverage()
    private var showBandCoverage = true
    private lateinit var zoomInButton: TextView
    private lateinit var zoomOutButton: TextView
    private lateinit var motionTracker: DriveMotionTracker
    private lateinit var servingStation: ServingStationOverlay
    private val carrierViews = mutableMapOf<Int, TextView>()
    private var showHandoverPoints = true
    private var showStationMarkers = true
    private var historyStartView = false
    private var pendingTrackStart: Boolean? = null
    private var showServingAntennas = true
    private var showServingLink = true
    private var inertialEnabled = true
    private var lastPositionAt = 0L
    private var lastPositionEstimated = false
    private var lastPositionAccuracy: Float? = null
    private var lastCityName = ""
    private var lastCityPoint: LatLng? = null
    private var restoredSessionTitle = ""
    private var restoredSessionId: String? = null
    private val sessionDisplayNames = ConcurrentHashMap<String, String>()
    private var currentDistanceMeters = 0.0
    private var pendingResumeSessionId: String? = null
    private var pendingResumePoints: List<DrivePoint> = emptyList()
    private val sessionCities = ConcurrentHashMap<String, String>()
    private val cityLookups = ConcurrentHashMap.newKeySet<String>()
    private val signalPanelValueViews = mutableMapOf<Int, TextView>()
    private val signalPanelProgressViews = mutableMapOf<Int, ProgressBar>()
    private val signalPanelTabs = mutableMapOf<Int, LinearLayout>()
    private var selectedSimSlot = 0
    private var latestSnapshots: Map<Int, NetworkPanelUiState> = emptyMap()
    private var baseMapMode = BaseMapMode.STANDARD

    private val fabBlue by lazy { Color.parseColor("#448AFF") }
    private val fabIconWhite by lazy {
        android.content.res.ColorStateList.valueOf(Color.WHITE)
    }
    private val overlayPrefs by lazy { getSharedPreferences(PREF_MAP_OVERLAY, Context.MODE_PRIVATE) }
    private var sectorDrawConfig = SectorDrawConfig(
        angleDeg = DEFAULT_SECTOR_ANGLE_DEG,
        radiusMeters = DEFAULT_SECTOR_RADIUS_METERS
    )
    private var markerSampleIntervalSec = DEFAULT_MARKER_SAMPLE_INTERVAL_SEC
    private val sessionTimeFormatter by lazy {
        SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
    }

    // ===== 外部传入测试点 =====
    private var previewMarker: Marker? = null
    // ===== 外部预览点进入态 =====
    private var hasPreviewTarget = false
    private var shouldSkipFirstFixCenter = false
    private val isPickMode: Boolean by lazy {
        intent.getBooleanExtra(EXTRA_PICK_MODE, false)
    }
    private var pickedMarker: Marker? = null
    private var pickedPointGcj: LatLng? = null
    private lateinit var fabPickConfirm: FloatingActionButton
    private lateinit var fabPickCancel: FloatingActionButton

    private val requestPerm =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val ok = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                    result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            if (ok) initMyLocation()
        }

    private val requestDriveLocation =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            drivePermissionPending = false
            if (hasLocPerm()) {
                initMyLocation()
                requestDriveStart()
            } else showDriveMessage(R.string.map_drive_location_required)
        }

    private val requestDriveNotification =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            drivePermissionPending = false
            // Denial does not prohibit foreground recording, but warn about hidden notifications.
            startDriveRecording()
        }

    private val requestDrivePhone =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            drivePermissionPending = false
            if (granted) {
                cellWatchJob?.cancel()
                cellWatchJob = null
                startWatchCellChanges()
                requestDriveStart()
            } else showDriveMessage(R.string.map_drive_phone_required)
        }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguageManager.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this)
        setContentView(root)

        mapView = MapView(this)
        root.addView(
            mapView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        mapView.onCreate(savedInstanceState)

        aMap = mapView.map
        aMap.uiSettings.apply {
            isZoomControlsEnabled = false
            isCompassEnabled = true
            isMyLocationButtonEnabled = false
        }
        baseMapMode = loadBaseMapMode()
        is3DMode = overlayPrefs.getBoolean("map_3d", false)
        applyMapTypeForCurrentMode()

        aMap.setOnMapTouchListener {
            if (followMyLocation) {
                followMyLocation = false
                aMap.stopAnimation()
                updateCenterFabUi()
            }
        }

        aMap.setOnCameraChangeListener(object : AMap.OnCameraChangeListener {
            override fun onCameraChange(position: com.amap.api.maps.model.CameraPosition?) {}
            override fun onCameraChangeFinish(position: com.amap.api.maps.model.CameraPosition?) {
                stationDistancePreview?.onCameraChanged()
                val now = SystemClock.elapsedRealtime()
                if (!followMyLocation || now - lastCameraOverlayRefreshAt >= 500L) {
                    lastCameraOverlayRefreshAt = now
                    updateZoomControls()
                    if (!isPickMode && ::servingStation.isInitialized) servingStation.onCameraChanged()
                    if (!isPickMode && ::sampler.isInitialized) sampler.refreshVisibleMarkers()
                }
                if (ignoreCameraMoveCancelOnce) {
                    ignoreCameraMoveCancelOnce = false
                    return
                }
            }
        })

        installWrappingInfoWindows()
        if (isPickMode) {
            nearbyStatus = TextView(this).apply {
                setPadding(dp(12), dp(8), dp(12), dp(8))
                setTextColor(Color.DKGRAY)
                background = panelBackground()
            }
            root.addView(nearbyStatus, FrameLayout.LayoutParams(-1, -2).apply {
                gravity = Gravity.TOP
                topMargin = statusBarHeightPx() + dp(8)
                leftMargin = dp(12); rightMargin = dp(12)
            })
            setupPickMode(root)
            seedPickPointIfNeeded()
        } else {
            dao = AppDb.get(applicationContext).drivePointDao()
            multiSnapshotter = TelephonySnapshotterMultiSim(this)
            panelRepository = NetworkPanelRepositoryMultiSim(this, multiSnapshotter)
            sectorDrawConfig = loadSectorDrawConfig()
            bandFilter = runCatching {
                DriveBandFilter(DriveBandFilter.parseLte(overlayPrefs.getString(KEY_POINT_LTE_BANDS, "").orEmpty()),
                    DriveBandFilter.parseNr(overlayPrefs.getString(KEY_POINT_NR_BANDS, "").orEmpty()))
            }.getOrDefault(DriveBandFilter())
            showBandCoverage = overlayPrefs.getBoolean(KEY_SHOW_BAND_COVERAGE, true)
            markerSampleIntervalSec = loadMarkerSampleIntervalSec()
            nearbyEnabled = overlayPrefs.getBoolean(KEY_NEARBY_ENABLED, false)
            nearbyRadiusKm = overlayPrefs.getInt(KEY_NEARBY_RADIUS_KM, 3).coerceIn(1, 10)
            nearbyBand = overlayPrefs.getString(KEY_NEARBY_BAND, null)?.takeIf { it.isNotBlank() }
            nearbyRat = overlayPrefs.getString(KEY_NEARBY_RAT, null)?.takeIf { it == "LTE" || it == "NR" }
            stationExclusion = runCatching {
                StationExclusion(DriveBandFilter.parseLte(overlayPrefs.getString(KEY_STATION_EXCLUDED_BANDS, "").orEmpty()),
                    StationExclusion.parseChannels(overlayPrefs.getString(KEY_STATION_EXCLUDED_CHANNELS, "").orEmpty()))
            }.getOrDefault(StationExclusion())

            showHandoverPoints = overlayPrefs.getBoolean("show_handover_points", true)
            showStationMarkers = overlayPrefs.getBoolean("show_station_markers", true)
            historyStartView = overlayPrefs.getBoolean("history_start_view", false)
            showServingAntennas = overlayPrefs.getBoolean("show_serving_antennas", true)
            showServingLink = overlayPrefs.getBoolean("show_serving_link", true)
            // The old manual-calibration preference defaulted to off. Automatic fusion has its own opt-out.
            inertialEnabled = overlayPrefs.getBoolean("inertial_auto_enabled", true)

            sampler = DriveSampler(
                aMap = aMap,
                scope = scope,
                position = {
                    val now = SystemClock.elapsedRealtime()
                    val reading = if (::motionTracker.isInitialized) motionTracker.engine.reading(now)
                        else DeadReckoningEngine.Reading(DeadReckoningEngine.Mode.NO_FIX, reason = "tracker_not_started")
                    val fix = samplePositions.select(now, reading, inertialEnabled)
                    driveLog("position source=${fix.source} reason=${fix.reason} outageMs=${reading.outageMillis} " +
                        if (::motionTracker.isInitialized) motionTracker.diagnostic(now) else "tracker_not_started")
                    fix.copy(mapMatchJson = lastMapMatchJson.takeIf { fix.located && fix.measuredAt == lastPositionAt })
                },
                onCoverageChanged = { coverage ->
                    bandCoverage = coverage
                    updateBandCoveragePanel()
                },
                onPointSelected = { point ->
                    if (restoredHistory != null) {
                        val station = servingStation.selectHistoryPoint(point)
                        updateSignalPanel(displayedSnapshots() + (point.simSlot to point.snapshot))
                        station.messageRes?.let { getString(it) } ?: getString(R.string.map_history_point_station_info,
                            station.name.orEmpty(), station.distanceMeters?.let { meters ->
                                if (meters < 1000) String.format(Locale.getDefault(), "%.0f m", meters)
                                else String.format(Locale.getDefault(), "%.2f km", meters / 1000)
                            }.orEmpty())
                    } else null
                },
                snapshots = { snapshotAllSims() },
                density = resources.displayMetrics.density,
                pointDp = 11f,
                handoverDp = 12f,
                pointHitDp = 28f,
                handoverHitDp = 30f,
                intervalMs = markerSampleIntervalSec * 1000L,
                maxMarkers = 4000,
                resolvePersistSessionId = { persistSessionId },
                onPersist = { points, sid ->
                    dao.recordBatch(points.map { it.toDriveEntity(sid) }, sessionCities[sid].orEmpty())
                    points.firstNotNullOfOrNull { it.latLng }?.let { resolveSessionCity(sid, it) }
                    driveLog("persist session=$sid rows=${points.size} located=${points.count { it.latLng != null }}")
                    val metadata = dao.getSession(sid)
                    withContext(Dispatchers.Main.immediate) {
                        if (currentSessionId == sid) {
                            hasNewPointsInSession = true
                            currentDistanceMeters = metadata?.distanceMeters ?: 0.0
                            if (!isDestroyed) updateSignalPanel(displayedSnapshots())
                        }
                    }
                },
                persistEnabled = { isDriving && !persistSessionId.isNullOrBlank() },
                onHandover = { state ->
                    if (latestSnapshots[selectedSimSlot]?.subId == state.subId && ::servingStation.isInitialized)
                        servingStation.update(state)
                }
            )

            sampler.bindClickToShowInfo()
            sampler.setBandFilter(bandFilter)

            lteOverlay = LteCellOverlayManager(
                aMap = aMap,
                api = apiService,
                localRepository = localRepository,
                scope = scope,
                cacheDao = AppDb.get(applicationContext).cellOverlayCacheDao(),
                cacheTtlMs = 7L * 24 * 3600_000L,
                serverCoord = ServerCoord.WGS84,
                maxHistoryMarkers = Int.MAX_VALUE
            )
            nrOverlay = NrCellOverlayManager(
                aMap = aMap,
                api = apiService,
                localRepository = localRepository,
                scope = scope,
                serverCoord = ServerCoord.WGS84,
                maxHistoryMarkers = Int.MAX_VALUE
            )
            nearbyLteOverlay = LteCellOverlayManager(
                aMap = aMap, api = apiService, localRepository = localRepository, scope = scope,
                cacheDao = AppDb.get(applicationContext).cellOverlayCacheDao(),
                serverCoord = ServerCoord.WGS84, maxHistoryMarkers = 1000, drawAntennas = false
            )
            nearbyNrOverlay = NrCellOverlayManager(
                aMap = aMap, api = apiService, localRepository = localRepository, scope = scope,
                serverCoord = ServerCoord.WGS84, maxHistoryMarkers = 1000, drawAntennas = false
            )
            probeAntennaLte = LteCellOverlayManager(aMap, apiService, localRepository, scope,
                AppDb.get(applicationContext).cellOverlayCacheDao(), maxHistoryMarkers = Int.MAX_VALUE,
                showStationMarkers = false)
            probeAntennaNr = NrCellOverlayManager(aMap, apiService, localRepository, scope,
                maxHistoryMarkers = Int.MAX_VALUE, showStationMarkers = false)
            stationDistancePreview = StationDistancePreview(aMap, density) { meters ->
                getString(R.string.map_station_preview_distance, if (meters < 1000)
                    String.format(Locale.getDefault(), "%.0f m", meters)
                    else String.format(Locale.getDefault(), "%.2f km", meters / 1000))
            }
            sampler.onMapMarkerSelected = { marker ->
                val station = marker?.let(::stationForMarker)
                if (probeAntennas.select(station)) renderProbeAntennas()
                if (marker != null && station != null && !isDriving && restoredHistory == null) {
                    stationDistancePreview?.select(marker.position)
                    stationDistancePreview?.updatePosition(stationUserPosition())
                    if (stationDistancePreview?.hasPosition != true) showDriveMessage(R.string.map_station_preview_location_pending)
                } else stationDistancePreview?.clear()
            }
            sampler.resolveStationMarker = { probeAntennas.selectedStationKey?.let(::markerForStation) }
            applySectorDrawConfigToOverlays()

            setupFabs(root)
            setupSignalPanel(root)
            setupMapControls(root)
            setupZoomControls(root)
            setupSignalLegend(root)
            setupBandCoverage(root)
            sampler.setHandoverVisible(showHandoverPoints)
            applyStationMarkerVisibility()
            servingStation = ServingStationOverlay(aMap, apiService, localRepository, scope, lteOverlay, nrOverlay, onStatus = { message, name ->
                servingStatus.text = if (name == null) getString(message) else getString(message, name)
            }, onLinked = ::saveLinkedStation)
            servingStation.setDisplay(showServingAntennas, showServingLink)
            servingStation.setExclusion(stationExclusion)
            motionTracker = DriveMotionTracker(this, ::onMotionReading)
            motionTracker.setInertialEnabled(inertialEnabled)
            motionTracker.engine.maxOutageMs = overlayPrefs.getInt("inertial_max_minutes", 10).coerceIn(1, 30) * 60_000L
            locationMarker = DriveLocationMarker(aMap, density).apply {
                onFrame = { point, bearing ->
                    if (followMyLocation && mapResumed && !isFirstFix && restoredHistory == null && !cameraControlAnimating)
                        followCameraFrame(point, bearing.toFloat())
                }
            }
            mapMatching = MapMatchingController(this, aMap, scope, mapStatusRow, ::onMapMatchResult, ::onFalconTrace)
            mapMatching?.setPathVisible(!bandFilter.active)
            aMap.setOnMapLoadedListener {
                aMap.uiSettings.isZoomControlsEnabled = false
                updateZoomControls()
                servingStation.onMapReady()
                sampler.refreshVisibleMarkers()
                renderNearbyResult()
                pendingTrackStart?.let { frameCurrentTrack(it) }
            }
            triggerTriangleOnceOnEnter()
            updateSignalPanel(snapshotAllSims())

            // ✅ 处理外部传入测试点
            handlePreviewPointIfNeeded()
        }

        if (hasLocPerm()) initMyLocation()
        else requestPerm.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

    private fun handlePreviewPointIfNeeded() {
        val lat = intent.getDoubleExtra(EXTRA_PREVIEW_LAT, Double.NaN)
        val lon = intent.getDoubleExtra(EXTRA_PREVIEW_LON, Double.NaN)
        if (lat.isNaN() || lon.isNaN()) return

        hasPreviewTarget = true
        shouldSkipFirstFixCenter = true
        followMyLocation = false
        updateCenterFabUi()

        val title = intent.getStringExtra(EXTRA_PREVIEW_TITLE).orEmpty().ifBlank { "目标位置" }
        val mode = intent.getStringExtra(EXTRA_PREVIEW_MODE).orEmpty().ifBlank { "UNKNOWN" }

        val point = LatLng(lat, lon)

        previewMarker?.remove()
        previewMarker = aMap.addMarker(
            MarkerOptions()
                .position(point)
                .title(title)
                .snippet(
                    when (mode) {
                        MODE_WGS84_RAW -> "WGS84 原始坐标"
                        MODE_GCJ02_CONVERTED -> "GCJ02 地图坐标"
                        else -> mode
                    }
                )
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE))
        )

        ignoreCameraMoveCancelOnce = true
        aMap.moveCamera(CameraUpdateFactory.newLatLngZoom(point, 18f))
        previewMarker?.showInfoWindow()
    }

    private fun setupPickMode(root: FrameLayout) {
        fabCenter = FloatingActionButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_mylocation)
            setOnClickListener {
                val p = lastLatLng ?: return@setOnClickListener
                val now = SystemClock.elapsedRealtime()
                val doubleTapWindow = 800L
                val isSecondClick = (now - lastCenterClickAt) <= doubleTapWindow
                lastCenterClickAt = now

                if (!isSecondClick) {
                    ignoreCameraMoveCancelOnce = true
                    val zoom = aMap.cameraPosition.zoom.coerceAtLeast(16f)
                    aMap.animateCamera(CameraUpdateFactory.newLatLngZoom(p, zoom))
                } else {
                    followMyLocation = !followMyLocation
                    updateCenterFabUi()
                    if (followMyLocation) {
                        applyFollowCamera(p, lastBearingDeg)
                    }
                }
            }
        }
        root.addView(
            fabCenter,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.END or Gravity.BOTTOM
                rightMargin = dp(18)
                bottomMargin = dp(28)
            }
        )
        updateCenterFabUi()

        fabPickCancel = FloatingActionButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setOnClickListener {
                setResult(RESULT_CANCELED)
                finish()
            }
        }
        root.addView(
            fabPickCancel,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.END or Gravity.BOTTOM
                rightMargin = dp(18)
                bottomMargin = dp(28 + 56 + 12)
            }
        )
        fabPickCancel.backgroundTintList =
            android.content.res.ColorStateList.valueOf(overlayColor(0xFFC62828.toInt()))
        applyFabIconUi(fabPickCancel)

        fabPickConfirm = FloatingActionButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_save)
            setOnClickListener { finishPickWithResult() }
        }
        root.addView(
            fabPickConfirm,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.END or Gravity.BOTTOM
                rightMargin = dp(18)
                bottomMargin = dp(28 + (56 + 12) * 2)
            }
        )
        applyFabDefaultUi(fabPickConfirm)

        aMap.setOnMapClickListener { point ->
            if (followMyLocation) {
                followMyLocation = false
                updateCenterFabUi()
            }
            updatePickedPoint(point, moveCamera = false)
        }
        aMap.setOnMarkerClickListener { marker ->
            if (marker == pickedMarker) {
                marker.showInfoWindow()
                true
            } else {
                false
            }
        }

        showMapStatus(getString(R.string.map_pick_hint))
    }

    private fun seedPickPointIfNeeded() {
        val initWgsLat = intent.getDoubleExtra(EXTRA_PICK_INIT_WGS_LAT, Double.NaN)
        val initWgsLon = intent.getDoubleExtra(EXTRA_PICK_INIT_WGS_LON, Double.NaN)
        if (initWgsLat.isNaN() || initWgsLon.isNaN()) return

        val (gcjLat, gcjLon) = Gcj02.wgs84ToGcj02(initWgsLat, initWgsLon)
        shouldSkipFirstFixCenter = true
        followMyLocation = false
        updateCenterFabUi()
        updatePickedPoint(
            pointGcj = LatLng(gcjLat, gcjLon),
            moveCamera = true
        )
    }

    private fun updatePickedPoint(pointGcj: LatLng, moveCamera: Boolean) {
        pickedPointGcj = pointGcj
        val (wgsLat, wgsLon) = Gcj02.gcj02ToWgs84(pointGcj.latitude, pointGcj.longitude)
        val title = intent.getStringExtra(EXTRA_PICK_TITLE).orEmpty()
            .ifBlank { getString(R.string.map_pick_default_title) }

        pickedMarker?.remove()
        pickedMarker = aMap.addMarker(
            MarkerOptions()
                .position(pointGcj)
                .title(title)
                .snippet(
                    "GCJ02: ${String.format(Locale.US, "%.6f", pointGcj.longitude)}, " +
                            "${String.format(Locale.US, "%.6f", pointGcj.latitude)}\n" +
                            "WGS84: ${String.format(Locale.US, "%.6f", wgsLon)}, " +
                            "${String.format(Locale.US, "%.6f", wgsLat)}"
                )
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_ORANGE))
        )
        pickedMarker?.showInfoWindow()

        if (moveCamera) {
            ignoreCameraMoveCancelOnce = true
            aMap.moveCamera(CameraUpdateFactory.newLatLngZoom(pointGcj, 18f))
        }
    }

    private fun finishPickWithResult() {
        val pointGcj = pickedPointGcj
        if (pointGcj == null) {
            showMapStatus(getString(R.string.map_pick_no_selection))
            return
        }

        val (wgsLat, wgsLon) = Gcj02.gcj02ToWgs84(pointGcj.latitude, pointGcj.longitude)
        val title = intent.getStringExtra(EXTRA_PICK_TITLE).orEmpty()
            .ifBlank { getString(R.string.map_pick_default_title) }

        setResult(
            RESULT_OK,
            Intent().apply {
                putExtra(EXTRA_PICK_RESULT_WGS_LAT, wgsLat)
                putExtra(EXTRA_PICK_RESULT_WGS_LON, wgsLon)
                putExtra(EXTRA_PICK_RESULT_GCJ_LAT, pointGcj.latitude)
                putExtra(EXTRA_PICK_RESULT_GCJ_LON, pointGcj.longitude)
                putExtra(EXTRA_PICK_RESULT_POI_NAME, title)
                putExtra(EXTRA_PICK_RESULT_ADDRESS, getString(R.string.follow_record_manual_pick_address))
            }
        )
        finish()
    }


    private fun setupFabs(root: FrameLayout) {
        fabActions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        fabActionsHost = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(fabActions, FrameLayout.LayoutParams(dp(48), -2))
        }
        root.addView(fabActionsHost, FrameLayout.LayoutParams(dp(48), -2).apply {
            gravity = Gravity.END or Gravity.BOTTOM
            rightMargin = dp(12)
            bottomMargin = navigationBarHeightPx() + dp(72)
        })
        fun addFab(action: (View) -> Unit, icon: Int): FloatingActionButton {
            val fab = FloatingActionButton(this).apply {
                customSize = dp(48)
                setMaxImageSize(dp(20))
                useCompatPadding = false
                setImageResource(icon)
                setOnClickListener(action)
            }
            fabActions.addView(fab, LinearLayout.LayoutParams(dp(48), dp(48)).apply {
                if (fabActions.childCount > 0) topMargin = dp(6)
            })
            applyFabDefaultUi(fab)
            return fab
        }
        fabCenter = addFab({
            val point = lastLatLng
            if (point != null) {
                val now = SystemClock.elapsedRealtime()
                val secondClick = now - lastCenterClickAt <= 800
                lastCenterClickAt = now
                if (secondClick) {
                    followMyLocation = !followMyLocation
                    updateCenterFabUi()
                    if (followMyLocation) applyFollowCamera(point, lastBearingDeg)
                } else {
                    ignoreCameraMoveCancelOnce = true
                    aMap.animateCamera(CameraUpdateFactory.newLatLngZoom(point, aMap.cameraPosition.zoom.coerceAtLeast(16f)))
                }
            }
        }, android.R.drawable.ic_menu_mylocation)
        fabCenter.contentDescription = getString(R.string.map_control_location)
        fabDrive = addFab({ toggleDrive() }, android.R.drawable.ic_media_play)
        fabDrive?.contentDescription = getString(R.string.map_control_record)
        fabMore = addFab({ anchor ->
            PopupMenu(this, anchor).apply {
                menu.add(0, 6, 0, R.string.map_show_station_markers).apply {
                    isCheckable = true
                    isChecked = showStationMarkers
                }
                menu.add(0, 4, 0, R.string.map_band_filter_title).apply {
                    isCheckable = true
                    isChecked = bandFilter.active
                }
                menu.add(0, 1, 1, R.string.map_drawing_probe_settings)
                menu.add(0, 5, 2, R.string.map_station_exclusion_title).apply {
                    isCheckable = true
                    isChecked = stationExclusion.active
                }
                menu.add(0, 2, 3, R.string.map_sessions)
                menu.add(0, 3, 4, R.string.map_clear_recording)
                menu.add(0, 7, 5, R.string.map_track_overview).isEnabled = ::sampler.isInitialized && sampler.recordedPoints().isNotEmpty()
                menu.add(0, 8, 6, R.string.map_track_start).isEnabled = ::sampler.isInitialized && sampler.recordedPoints().isNotEmpty()
                menu.addSubMenu(0, 9, 7, R.string.map_history_initial_view).apply {
                    add(1, 10, 0, R.string.map_track_overview).isChecked = !historyStartView
                    add(1, 11, 1, R.string.map_track_start).isChecked = historyStartView
                    setGroupCheckable(1, true, true)
                }
                setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        6 -> {
                            showStationMarkers = !showStationMarkers
                            overlayPrefs.edit().putBoolean("show_station_markers", showStationMarkers).apply()
                            applyStationMarkerVisibility()
                        }
                        7 -> frameCurrentTrack(start = false)
                        8 -> frameCurrentTrack(start = true)
                        10, 11 -> {
                            historyStartView = item.itemId == 11
                            overlayPrefs.edit().putBoolean("history_start_view", historyStartView).apply()
                            if (restoredHistory != null) frameCurrentTrack(historyStartView)
                        }
                        4 -> showBandFilterDialog()
                        1 -> showSectorConfigDialog()
                        5 -> showStationExclusionDialog()
                        2 -> showSessionRestoreDialog()
                        3 -> newDialogBuilder().setTitle(R.string.map_clear_recording)
                            .setMessage(R.string.map_clear_recording_confirm)
                            .setPositiveButton(android.R.string.ok) { _, _ -> clearCurrentSession() }
                            .setNegativeButton(android.R.string.cancel, null).show()
                    }
                    true
                }
                show()
            }
        }, android.R.drawable.ic_menu_more)
        fabMore.contentDescription = getString(R.string.map_control_more)
        fabActions.removeView(fabMore)
        fabActions.addView(fabMore, 0)
        repeat(fabActions.childCount) { index ->
            val child = fabActions.getChildAt(index)
            child.layoutParams = (child.layoutParams as LinearLayout.LayoutParams).apply { topMargin = if (index > 0) dp(6) else 0 }
        }
        updateCenterFabUi()
        applyDriveUi(isDriving)
    }

    private fun overlayTextColor(): Int = if (isSystemNightMode()) 0xFFF0F4FA.toInt() else 0xFF263547.toInt()

    private fun overlayColor(color: Int): Int = Color.argb(210, Color.red(color), Color.green(color), Color.blue(color))

    private fun panelBackground(radiusDp: Int = 12) = GradientDrawable().apply {
        setColor(if (isSystemNightMode()) 0xCC182331.toInt() else 0xCCFFFFFF.toInt())
        cornerRadius = dp(radiusDp).toFloat()
        setStroke(dp(1), if (isSystemNightMode()) 0x40FFFFFF else 0x30FFFFFF)
    }

    private fun setupSignalPanel(root: FrameLayout) {
        signalPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(5), dp(8), dp(5))
            background = panelBackground()
            elevation = dp(6).toFloat()
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        (0..1).forEach { slot ->
            val tab = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(8), dp(4), dp(8), dp(4))
                setOnClickListener {
                    selectedSimSlot = slot
                    sampler.setVisibleSimSlot(slot)
                    updateSignalPanel(displayedSnapshots())
                    if (restoredHistory != null) {
                        refreshHistoryStation()
                        frameCurrentTrack(historyStartView)
                    }
                    else {
                        servingStation.clear()
                        servingStation.update(displayedSnapshots()[slot])
                    }
                    updateServingPosition()
                }
                contentDescription = "SIM${slot + 1}"
            }
            val carrier = TextView(this).apply {
                textSize = 12f
                setSingleLine()
                ellipsize = TextUtils.TruncateAt.MARQUEE
                marqueeRepeatLimit = -1
                isSelected = true
            }
            carrierViews[slot] = carrier
            val label = TextView(this).apply { textSize = 13f; setTextColor(overlayTextColor()); setSingleLine(); ellipsize = TextUtils.TruncateAt.END }
            val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
            signalPanelTabs[slot] = tab
            signalPanelValueViews[slot] = label
            signalPanelProgressViews[slot] = progress
            tab.addView(carrier, LinearLayout.LayoutParams(-1, dp(19)))
            tab.addView(label, LinearLayout.LayoutParams(-1, dp(22)))
            tab.addView(progress, LinearLayout.LayoutParams(-1, dp(3)))
            row.addView(tab, LinearLayout.LayoutParams(0, -2, 1f))
        }
        signalPanel.addView(row, LinearLayout.LayoutParams(-1, -2))
        mapStatusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        servingStatus = TextView(this).apply {
            textSize = 11f
            setTextColor(overlayTextColor())
            setPadding(dp(4), dp(4), dp(4), dp(2))
            setSingleLine()
            ellipsize = TextUtils.TruncateAt.END
            setText(R.string.map_serving_no_match)
        }
        mapStatusRow.addView(servingStatus, LinearLayout.LayoutParams(0, dp(26), 1f))
        signalPanel.addView(mapStatusRow, LinearLayout.LayoutParams(-1, -2))
        nearbyStatus = TextView(this).apply {
            textSize = 11f
            setTextColor(overlayTextColor())
            setPadding(dp(4), dp(3), dp(4), 0)
            setSingleLine()
            ellipsize = TextUtils.TruncateAt.END
            visibility = View.GONE
        }
        signalPanel.addView(nearbyStatus, LinearLayout.LayoutParams(-1, -2))
        root.addView(signalPanel, FrameLayout.LayoutParams(-1, -2).apply {
            gravity = Gravity.TOP
            topMargin = statusBarHeightPx() + dp(8)
            leftMargin = dp(12)
            rightMargin = dp(12)
        })
    }

    private fun setupMapControls(root: FrameLayout) {
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val controlsHost = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(controls, FrameLayout.LayoutParams(dp(48), -2))
        }
        root.addView(controlsHost, FrameLayout.LayoutParams(dp(48), -2).apply {
            gravity = Gravity.TOP or Gravity.END
            rightMargin = dp(12)
        })
        fun button(label: String, action: () -> Unit): TextView {
            val view = TextView(this).apply {
                text = label
                textSize = 11f
                setTextColor(overlayTextColor())
                gravity = Gravity.CENTER
                background = panelBackground()
                elevation = dp(2).toFloat()
                isClickable = true
                isFocusable = true
                setOnClickListener { action() }
            }
            controls.addView(view, LinearLayout.LayoutParams(dp(48), dp(44)).apply { bottomMargin = dp(6) })
            return view
        }
        mapTypeButton = button("") { toggleBaseMapMode() }
        viewModeButton = button("") { toggle2D3D() }
        speedView = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 11f
            setTextColor(overlayTextColor())
            setPadding(dp(2), dp(5), dp(2), dp(5))
            background = panelBackground()
            text = getString(R.string.map_speed_waiting)
        }
        controls.addView(speedView, LinearLayout.LayoutParams(dp(48), dp(62)))
        fun fitControls() {
            root.post {
                if (isDestroyed || root.height == 0) return@post
                val top = signalPanel.bottom + dp(12)
                val bottomMargin = navigationBarHeightPx() + dp(72)
                val available = (root.height - bottomMargin - top).coerceAtLeast(0)
                val compact = available < dp(332)
                val orientation = if (compact) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
                val width = dp(if (compact) 156 else 48)
                listOf(controls, fabActions).forEach { group ->
                    if (group.orientation != orientation) {
                        group.orientation = orientation
                        repeat(group.childCount) { index ->
                            val child = group.getChildAt(index)
                            val params = child.layoutParams as LinearLayout.LayoutParams
                            params.topMargin = if (!compact && group === fabActions && index > 0) dp(6) else 0
                            params.bottomMargin = if (!compact && group === controls && index < 2) dp(6) else 0
                            params.rightMargin = if (compact && index < 2) dp(6) else 0
                            child.layoutParams = params
                        }
                    }
                    if (group.layoutParams.width != width) group.layoutParams = group.layoutParams.apply { this.width = width }
                }
                val limit = ((available - dp(12)) / 2).coerceAtLeast(1)
                val upper = controlsHost.layoutParams as FrameLayout.LayoutParams
                val upperHeight = minOf(controls.measuredHeight, limit).coerceAtLeast(1)
                if (upper.width != width || upper.height != upperHeight || upper.topMargin != top) {
                    upper.width = width; upper.height = upperHeight; upper.topMargin = top
                    controlsHost.layoutParams = upper
                }
                val lower = fabActionsHost.layoutParams as FrameLayout.LayoutParams
                val lowerHeight = minOf(fabActions.measuredHeight, limit).coerceAtLeast(1)
                if (lower.width != width || lower.height != lowerHeight || lower.bottomMargin != bottomMargin) {
                    lower.width = width; lower.height = lowerHeight; lower.bottomMargin = bottomMargin
                    fabActionsHost.layoutParams = lower
                }
            }
        }
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitControls() }
        signalPanel.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitControls() }
        updateMapTypeFabUi()
        update2D3DFabUi()
    }

    private fun navigationBarHeightPx(): Int = androidx.core.view.ViewCompat.getRootWindowInsets(mapView)
        ?.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0

    private fun setupZoomControls(root: FrameLayout) {
        val bar = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            background = panelBackground(22)
            elevation = dp(3).toFloat()
        }
        fun button(label: String, description: Int, action: () -> Unit) = TextView(this).apply {
            text = label
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(overlayTextColor())
            contentDescription = getString(description)
            isFocusable = true
            setOnClickListener { action() }
            bar.addView(this, LinearLayout.LayoutParams(dp(48), dp(44)))
        }
        zoomOutButton = button("−", R.string.map_zoom_out) { animateMapControl(CameraUpdateFactory.zoomOut()) }
        bar.addView(View(this).apply { setBackgroundColor(0x30445566) }, LinearLayout.LayoutParams(dp(1), dp(20)))
        zoomInButton = button("+", R.string.map_zoom_in) { animateMapControl(CameraUpdateFactory.zoomIn()) }
        root.addView(bar, FrameLayout.LayoutParams(-2, dp(44)).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = navigationBarHeightPx() + dp(16)
        })
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val params = bar.layoutParams as FrameLayout.LayoutParams
            val margin = navigationBarHeightPx() + dp(16)
            if (params.bottomMargin != margin) { params.bottomMargin = margin; bar.layoutParams = params }
        }
        updateZoomControls()
    }

    private fun updateZoomControls() {
        if (!::zoomInButton.isInitialized) return
        val zoom = aMap.cameraPosition.zoom
        zoomInButton.isEnabled = zoom < aMap.maxZoomLevel - 0.01f
        zoomOutButton.isEnabled = zoom > aMap.minZoomLevel + 0.01f
        zoomInButton.alpha = if (zoomInButton.isEnabled) 1f else 0.35f
        zoomOutButton.alpha = if (zoomOutButton.isEnabled) 1f else 0.35f
    }

    private fun setupSignalLegend(root: FrameLayout) {
        val legend = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(7), dp(10), dp(7))
            background = panelBackground()
            elevation = dp(2).toFloat()
            contentDescription = getString(R.string.map_rsrp_legend)
        }
        legend.addView(TextView(this).apply {
            text = "RSRP · dBm"
            textSize = 11f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(overlayTextColor())
        })
        DriveStyle.signalBands.forEach { band ->
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(3), 0, 0) }
            row.addView(View(this).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(band.color) }
            }, LinearLayout.LayoutParams(dp(8), dp(8)).apply { rightMargin = dp(7) })
            row.addView(TextView(this).apply {
                text = band.rangeLabel ?: getString(R.string.map_signal_unavailable)
                textSize = 10f
                setTextColor(overlayTextColor())
            })
            legend.addView(row)
        }
        root.addView(legend, FrameLayout.LayoutParams(-2, -2).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            leftMargin = dp(12)
            bottomMargin = navigationBarHeightPx() + dp(72)
        })
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val params = legend.layoutParams as FrameLayout.LayoutParams
            val margin = navigationBarHeightPx() + dp(72)
            if (params.bottomMargin != margin) { params.bottomMargin = margin; legend.layoutParams = params }
        }
    }

    private fun installWrappingInfoWindows() {
        fun copy(marker: Marker) {
            val text = listOfNotNull(marker.title?.takeIf { it.isNotBlank() }, marker.snippet?.takeIf { it.isNotBlank() })
                .joinToString("\n")
            if (text.isNotEmpty()) {
                getSystemService(android.content.ClipboardManager::class.java)
                    .setPrimaryClip(android.content.ClipData.newPlainText(marker.title.orEmpty(), text))
                android.widget.Toast.makeText(this, R.string.map_bubble_copied, android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        aMap.setOnInfoWindowClickListener { copy(it) }
        aMap.setCommonInfoWindowAdapter(MarkerInfoWindowAdapter { marker ->
            LinearLayout(this@AmapMapActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(18))
                background = MapBubbleBackground(resources.displayMetrics.density, isSystemNightMode())
                addView(TextView(context).apply {
                    text = marker.title.orEmpty()
                    maxWidth = minOf(dp(280), resources.displayMetrics.widthPixels - dp(64))
                    textSize = 14f
                    setTextColor(overlayTextColor())
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setSingleLine(false)
                })
                addView(TextView(context).apply {
                    text = marker.snippet.orEmpty()
                    maxWidth = minOf(dp(280), resources.displayMetrics.widthPixels - dp(64))
                    textSize = 12f
                    setTextColor(overlayTextColor())
                    setSingleLine(false)
                })
                addView(LinearLayout(context).apply {
                    gravity = Gravity.END or Gravity.CENTER_VERTICAL
                    fun action(label: Int, click: (android.widget.Button) -> Unit) = android.widget.Button(context).apply {
                        setText(label); textSize = 12f; isAllCaps = false
                        backgroundTintList = android.content.res.ColorStateList.valueOf(Color.TRANSPARENT)
                        setTextColor(if (isSystemNightMode()) 0xFF70B9FF.toInt() else 0xFF1679C9.toInt())
                        minimumHeight = dp(40); minHeight = dp(40); minWidth = dp(56)
                        setPadding(dp(8), 0, dp(8), 0)
                        setOnClickListener { click(this) }
                        addView(this, LinearLayout.LayoutParams(-2, dp(40)))
                    }
                    stationForMarker(marker)?.let { station ->
                        action(if (probeAntennas.isPinned(station)) R.string.map_station_unpin else R.string.map_station_pin) { button ->
                            val pinned = probeAntennas.togglePin(station)
                            button.setText(if (pinned) R.string.map_station_unpin else R.string.map_station_pin)
                            renderProbeAntennas()
                        }
                    }
                    action(R.string.map_bubble_copy_action) { copy(marker) }
                })
            }
        })
    }

    private fun stationForMarker(marker: Marker): ProbedStation? {
        if (!::nearbyLteOverlay.isInitialized) return null
        // The serving overlays also contain session stations, including those outside the probe radius.
        (nearbyLteOverlay.nearbyStation(marker) ?: lteOverlay.nearbyStation(marker))?.let { (key, rows) ->
            return ProbedStation("LTE|$key", lte = rows)
        }
        (nearbyNrOverlay.nearbyStation(marker) ?: nrOverlay.nearbyStation(marker))?.let { (key, rows) ->
            return ProbedStation("NR|$key", nr = rows)
        }
        return null
    }

    private fun markerForStation(key: String): Marker? {
        val stationKey = key.substringAfter('|')
        return when (key.substringBefore('|')) {
            "LTE" -> nearbyLteOverlay.stationMarker(stationKey) ?: lteOverlay.stationMarker(stationKey)
            "NR" -> nearbyNrOverlay.stationMarker(stationKey) ?: nrOverlay.stationMarker(stationKey)
            else -> null
        }
    }

    private fun renderProbeAntennas() {
        if (!::probeAntennaLte.isInitialized) return
        val (lteRows, nrRows) = probeAntennas.displayed(stationExclusion)
        probeAntennaLte.renderNearbyCells(lteRows)
        probeAntennaNr.renderNearbyCells(nrRows)
    }

    private fun onMotionReading(reading: DeadReckoningEngine.Reading) {
        if (isDestroyed || !::speedView.isInitialized) return
        val now = SystemClock.elapsedRealtime()
        val sdkFresh = samplePositions.reliableGpsFresh(now)
        val speed = (if (sdkFresh && lastAmapIsGps) lastAmapSpeed ?: reading.speedMps else reading.speedMps)
            ?.let { String.format(Locale.getDefault(), "%.1f", it * 3.6) } ?: "—"
        val readiness = if (reading.mode == DeadReckoningEngine.Mode.ESTIMATED)
            getString(R.string.map_inertial_estimating, reading.outageMillis / 1000)
            else getString(if (motionTracker.engine.isReady(now)) R.string.map_inertial_ready else R.string.map_inertial_not_ready)
        speedView.text = "$speed\nkm/h\n$readiness"
        speedView.contentDescription = "$speed km/h · $readiness"
        if (!sdkFresh && reading.lat != null && reading.lng != null && reading.mode == DeadReckoningEngine.Mode.ESTIMATED) {
            val (lat, lng) = Gcj02.wgs84ToGcj02(reading.lat, reading.lng)
            val point = LatLng(lat, lng)
            acceptMapPosition(point, reading.bearing?.toFloat(), reading.mode == DeadReckoningEngine.Mode.ESTIMATED,
                reading.uncertaintyMeters?.toFloat(), Triple(reading.lat, reading.lng, "WGS84_ESTIMATED"),
                reading.speedMps, motionTracker.engine.isReady(now), reading.measuredAtMillis)
            if (mapResumed) amapListener?.onLocationChanged(Location("drive").apply {
                latitude = lat; longitude = lng
                accuracy = reading.uncertaintyMeters?.toFloat() ?: 10f
                time = System.currentTimeMillis()
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                reading.speedMps?.let { this.speed = it.toFloat() }
                reading.bearing?.let { bearing = it.toFloat() }
            })
        } else if (!sdkFresh && reading.mode == DeadReckoningEngine.Mode.NO_FIX) {
            mapMatching?.qualityLost()
        }
    }

    private fun acceptMapPosition(point: LatLng, bearing: Float?, estimated: Boolean, accuracy: Float?,
        raw: Triple<Double?, Double?, String?> = Triple(null, null, null), speedMps: Double? = null,
        speedReliable: Boolean = false, fixAt: Long = SystemClock.elapsedRealtime()) {
        lastLatLng = point
        lastRawPosition = raw
        lastPositionAt = fixAt
        lastPositionEstimated = estimated
        lastPositionAccuracy = accuracy
        lastBearingDeg = driveCourse.update(fixAt, point.latitude, point.longitude, (accuracy ?: 100f).toDouble(),
            speedMps, bearing?.toDouble()?.takeIf { estimated || speedReliable },
            estimated || lastAmapIsGps && (accuracy ?: 100f) <= 35f)?.toFloat()
        syncMapMatchingState()
        if (mapMatching?.enabled == true) {
            val wgs = if (raw.third?.startsWith("WGS84", true) == true && raw.first != null && raw.second != null)
                raw.first!! to raw.second!! else Gcj02.gcj02ToWgs84(point.latitude, point.longitude)
            mapMatching?.accept(MatchFix(RoutePosition(wgs.first, wgs.second), fixAt,
                if (estimated) MatchSource.IMU else if (lastAmapIsGps) MatchSource.GNSS else MatchSource.SDK,
                speedMps, bearing?.toDouble(), accuracy?.toDouble() ?: 999.0, speedReliable,
                System.currentTimeMillis() - (SystemClock.elapsedRealtime() - fixAt).coerceAtLeast(0)))
        }
        if (!mapResumed) return
        updateDirectionDisplay()
        locationMarker?.update(point, lastBearingDeg?.toDouble(), estimated, restoredHistory == null && driveCourse.isMoving)
        if (isFirstFix) {
            isFirstFix = false
            if (shouldSkipFirstFixCenter) shouldSkipFirstFixCenter = false
            else {
                ignoreCameraMoveCancelOnce = true
                aMap.moveCamera(CameraUpdateFactory.newLatLngZoom(point, 17f))
            }
        }
        if (!isPickMode) {
            updateServingPosition()
            probeNearbyCells()
        }
    }

    private fun stationUserPosition(): LatLng? =
        if (restoredHistory != null) visibleHistory?.pointsBySlot?.get(selectedSimSlot)?.firstNotNullOfOrNull { it.latLng }
        else lastLatLng?.takeIf {
            SystemClock.elapsedRealtime() - lastPositionAt in 0..5000 &&
                (lastPositionEstimated || (lastPositionAccuracy ?: Float.MAX_VALUE) <= 100f)
        }

    private fun updateServingPosition() {
        val position = stationUserPosition()
        if (::servingStation.isInitialized) servingStation.updatePosition(position)
        // Manual measuring keeps a valid live fix even if the next GPS sample is late.
        // Serving-station matching retains its stricter freshness/accuracy checks above.
        if (restoredHistory == null) stationDistancePreview?.updatePosition(position)
        if (isDriving || restoredHistory != null) stationDistancePreview?.clear()
    }

    private fun onMapMatchResult(result: MatchResult?, metadata: String?) {
        lastMapMatchJson = metadata ?: result?.toJson()
        // Delayed cloud paths correct timestamped recorded points, never the current location marker.
    }

    private fun onFalconTrace(trace: FalconTrace) {
        val sid = if (restoredHistory != null) restoredSessionId else persistSessionId
        if (sid == null || !::sampler.isInitialized) return
        val updates = sampler.applyFalconTrace(trace)
        if (updates.isEmpty()) return
        correctionWrites.enqueue(updates, sid)
        if (restoredHistory != null) restoredHistory = DriveHistory(sampler.recordedPoints())
    }

    private fun saveLinkedStation(state: NetworkPanelUiState, snapshot: DriveStationSnapshot, position: LatLng) {
        val sid = persistSessionId ?: return
        if (!isDriving || finishingDrive || restoredHistory != null) return
        val slot = latestSnapshots.entries.firstOrNull { it.value.subId == state.subId }?.key ?: return
        val cellKey = DriveStyle.cellKey(state)
        if (!linkedStationKeys.add("$sid|$slot|$cellKey")) return
        stationWrites.enqueue(listOf(DriveStationEntity(sid, slot, cellKey, System.currentTimeMillis(),
            position.latitude, position.longitude, DriveStationJson.encode(snapshot))), sid)
    }

    private fun showMapStatus(message: String) {
        if (::nearbyStatus.isInitialized) { nearbyStatus.text = message; nearbyStatus.visibility = View.VISIBLE }
        Logger.log("Map: $message")
    }

    private fun statusBarHeightPx(): Int {
        androidx.core.view.ViewCompat.getRootWindowInsets(mapView)
            ?.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars() or androidx.core.view.WindowInsetsCompat.Type.displayCutout())
            ?.top?.takeIf { it > 0 }?.let { return it }
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else dp(24)
    }

    private fun updateSignalPanel(states: Map<Int, NetworkPanelUiState>) {
        if (!::signalPanel.isInitialized) return
        syncMapMatchingState()
        restoredHistory?.let { mapMatching?.showHistory(it.pointsBySlot[selectedSimSlot].orEmpty()) }
        (0..1).forEach { slot ->
            val state = states[slot]
            carrierViews[slot]?.apply {
                val label = "SIM${slot + 1} · ${state?.operatorName?.takeIf { it.isNotBlank() && it != "-" } ?: "—"}"
                if (text.toString() != label) text = label
                isSelected = true
                setTextColor(if (selectedSimSlot == slot) fabBlue else overlayTextColor())
            }
            val rsrp = state?.let {
                val raw = if (it.cellType.equals("NR", true) && it.ssRsrp != "-") it.ssRsrp else it.rsrp
                parseSignalDbm(raw)
            }
            signalPanelValueViews[slot]?.apply {
                val rat = state?.cellType?.takeIf { it != "-" } ?: "-"
                val historyPoints = visibleHistory?.pointsBySlot?.get(slot)
                text = if (restoredHistory != null && historyPoints.isNullOrEmpty()) {
                    getString(if (bandFilter.active) R.string.map_band_filter_sim_empty else R.string.map_history_sim_empty, slot + 1)
                } else {
                    "$rat ${driveBandLabel(rat, state?.band.orEmpty())} · ${rsrp?.let { "$it dBm" } ?: "—"}"
                }
                setTextColor(if (selectedSimSlot == slot) fabBlue else overlayTextColor())
            }
            signalPanelTabs[slot]?.apply {
                isSelected = selectedSimSlot == slot
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(if (selectedSimSlot == slot) 0x18448AFF else Color.TRANSPARENT)
                    cornerRadius = dp(8).toFloat()
                }
            }
            signalPanelProgressViews[slot]?.apply {
                progress = rsrp?.let { ((it + 140) * 100 / 70).coerceIn(0, 100) } ?: 0
                progressTintList = android.content.res.ColorStateList.valueOf(
                    DriveStyle.colorByRsrp(rsrp)
                )
            }
            carrierViews[slot]?.isSelected = true
        }
    }

    private fun parseSignalDbm(raw: String): Int? =
        Regex("""-?\d+""").find(raw.trim())?.value?.toIntOrNull()

    // Header and both point streams use exactly the same subscription callbacks.
    // Never poll allCellInfo directly or invent a SIM1 snapshot on failure.
    private fun snapshotAllSims(): Map<Int, NetworkPanelUiState> = latestSnapshots

    private fun displayedSnapshots(): Map<Int, NetworkPanelUiState> =
        if (restoredHistory != null) visibleHistory?.snapshots.orEmpty() else latestSnapshots

    private fun setupBandCoverage(root: FrameLayout) {
        bandCoveragePanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = panelBackground()
            elevation = dp(3).toFloat()
        }
        fun label(size: Float) = TextView(this).apply {
            textSize = size
            setTextColor(overlayTextColor())
            setSingleLine()
            ellipsize = TextUtils.TruncateAt.END
            bandCoveragePanel.addView(this)
        }
        bandCoverageTitle = label(13f).apply { setTypeface(typeface, android.graphics.Typeface.BOLD) }
        bandCoverageBands = label(11f).apply {
            setSingleLine(false)
            ellipsize = null
            setPadding(0, dp(2), 0, dp(3))
        }
        bandCoverageDistance = label(12f)
        bandCoverageNote = label(10f)
        label(10f).apply { setSingleLine(false); ellipsize = null; setText(R.string.map_band_coverage_route_note) }
        root.addView(bandCoveragePanel, FrameLayout.LayoutParams(dp(200), -2))
        fun fit() {
            root.post {
                if (isDestroyed || root.height == 0) return@post
                val portrait = root.height >= root.width
                val params = bandCoveragePanel.layoutParams as FrameLayout.LayoutParams
                val gravity = if (portrait) Gravity.TOP or Gravity.START else Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                val top = if (portrait) signalPanel.bottom + dp(12) else 0
                val left = if (portrait) dp(12) else 0
                val bottom = if (portrait) 0 else navigationBarHeightPx() + dp(72)
                if (params.gravity != gravity || params.topMargin != top || params.leftMargin != left || params.bottomMargin != bottom) {
                    params.gravity = gravity; params.topMargin = top; params.leftMargin = left; params.bottomMargin = bottom
                    bandCoveragePanel.layoutParams = params
                }
            }
        }
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fit() }
        signalPanel.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fit() }
        updateBandCoveragePanel()
    }

    private fun updateBandCoveragePanel() {
        if (!::bandCoveragePanel.isInitialized) return
        bandCoveragePanel.visibility = if (bandFilter.active && showBandCoverage) View.VISIBLE else View.GONE
        val percent = bandCoverage.fraction?.let { String.format(Locale.getDefault(), "%.1f%%", it * 100) } ?: "—"
        bandCoverageTitle.text = getString(R.string.map_band_coverage_title, percent)
        bandCoverageBands.text = bandFilter.summary("\n")
        bandCoverageDistance.text = getString(R.string.map_band_coverage_distance,
            formatDistance(bandCoverage.coveredMeters), formatDistance(bandCoverage.totalMeters))
        bandCoverageNote.text = getString(R.string.map_band_coverage_note, selectedSimSlot + 1)
        bandCoveragePanel.contentDescription = "${bandFilter.summary()} · ${bandCoverageTitle.text} · ${bandCoverageDistance.text} · ${bandCoverageNote.text}"
    }

    private fun showBandFilterDialog() {
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        form.addView(TextView(this).apply {
            setText(R.string.map_band_filter_description)
            textSize = 13f
            setPadding(0, 0, 0, dp(12))
        })
        fun field(label: Int, input: String): Pair<TextInputLayout, TextInputEditText> {
            val edit = TextInputEditText(this).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                setSingleLine()
                setText(input)
            }
            val layout = TextInputLayout(this).apply { hint = getString(label); addView(edit) }
            form.addView(layout, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
            return layout to edit
        }
        val (lteLayout, lteEdit) = field(R.string.map_band_filter_lte, bandFilter.lteInput())
        val (nrLayout, nrEdit) = field(R.string.map_band_filter_nr, bandFilter.nrInput())
        lteEdit.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_NEXT
        nrEdit.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
        val coverageSwitch = SwitchMaterial(this).apply {
            setText(R.string.map_band_coverage_show)
            isChecked = showBandCoverage
        }
        form.addView(coverageSwitch, LinearLayout.LayoutParams(-1, -2))
        form.addView(TextView(this).apply {
            setText(R.string.map_band_coverage_method)
            textSize = 12f
            setPadding(0, dp(4), 0, dp(4))
        })
        val dialog = newDialogBuilder().setTitle(R.string.map_band_filter_title)
            .setView(ScrollView(this).apply { addView(form) })
            .setPositiveButton(R.string.map_band_filter_apply, null)
            .setNeutralButton(R.string.map_band_filter_clear) { _, _ -> applyBandFilter(DriveBandFilter()) }
            .setNegativeButton(android.R.string.cancel, null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                lteLayout.error = null; nrLayout.error = null
                val lte = runCatching { DriveBandFilter.parseLte(lteEdit.text.toString()) }.getOrElse {
                    lteLayout.error = getString(R.string.map_band_filter_lte_error); return@setOnClickListener
                }
                val nr = runCatching { DriveBandFilter.parseNr(nrEdit.text.toString()) }.getOrElse {
                    nrLayout.error = getString(R.string.map_band_filter_nr_error); return@setOnClickListener
                }
                showBandCoverage = coverageSwitch.isChecked
                overlayPrefs.edit().putBoolean(KEY_SHOW_BAND_COVERAGE, showBandCoverage).apply()
                applyBandFilter(DriveBandFilter(lte, nr))
                dialog.dismiss()
            }
        }
        nrEdit.setOnEditorActionListener { _, action, _ ->
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                true
            } else false
        }
        dialog.show()
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }

    private fun applyStationMarkerVisibility() {
        if (::lteOverlay.isInitialized) lteOverlay.setStationMarkersVisible(showStationMarkers)
        if (::nrOverlay.isInitialized) nrOverlay.setStationMarkersVisible(showStationMarkers)
        if (::nearbyLteOverlay.isInitialized) nearbyLteOverlay.setStationMarkersVisible(showStationMarkers)
        if (::nearbyNrOverlay.isInitialized) nearbyNrOverlay.setStationMarkersVisible(showStationMarkers)
    }

    private fun frameCurrentTrack(start: Boolean) {
        val points = (visibleHistory?.pointsBySlot?.get(selectedSimSlot)
            ?: if (restoredHistory == null) sampler.recordedPoints().filter { it.simSlot == selectedSimSlot && bandFilter.matches(it.snapshot) }
            else emptyList()).filter { it.latLng?.let { p -> driveValidPosition(p.latitude, p.longitude, 0f) } == true }
        pendingTrackStart = null
        if (points.isEmpty()) return
        followMyLocation = false
        shouldSkipFirstFixCenter = true
        updateCenterFabUi()
        if (mapView.width <= 0 || mapView.height <= 0) { pendingTrackStart = start; return }
        ignoreCameraMoveCancelOnce = true
        val first = points.minBy { it.ts }.latLng!!
        if (start || points.all { it.latLng == first }) {
            aMap.moveCamera(CameraUpdateFactory.newLatLngZoom(first, 17f))
        } else {
            val bounds = com.amap.api.maps.model.LatLngBounds.builder()
            points.forEach { bounds.include(it.latLng!!) }
            val padding = dp(80).coerceAtMost(minOf(mapView.width, mapView.height) / 4)
            aMap.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds.build(), mapView.width, mapView.height, padding))
        }
    }

    private fun applyBandFilter(filter: DriveBandFilter) {
        bandFilter = filter
        overlayPrefs.edit().putString(KEY_POINT_LTE_BANDS, filter.lteInput())
            .putString(KEY_POINT_NR_BANDS, filter.nrInput()).apply()
        visibleHistory = restoredHistory?.filteredBy(filter)
        sampler.setBandFilter(filter)
        // The cloud path has no band labels; it must not show an unfiltered route over filtered points.
        mapMatching?.setPathVisible(!filter.active)
        visibleHistory?.let { history ->
            selectedSimSlot = history.initialSlot(selectedSimSlot)
            sampler.setVisibleSimSlot(selectedSimSlot)
            refreshHistoryStation()
            frameCurrentTrack(historyStartView)
        }
        updateBandCoveragePanel()
        updateSignalPanel(displayedSnapshots())
        updateServingPosition()
        if (restoredHistory != null) showHistoryPointCount()
    }

    private fun refreshHistoryStation() {
        val history = visibleHistory ?: return
        val points = history.pointsBySlot[selectedSimSlot].orEmpty()
        val keys = points.flatMap { listOf(it.cellKey, DriveStyle.cellKey(it.snapshot)) }.toHashSet()
        val stations = restoredStations.filter { it.simSlot == selectedSimSlot && it.cellKey in keys }
        servingStation.clear()
        val firstLocated = points.firstOrNull { it.latLng != null }
        servingStation.updatePosition(firstLocated?.latLng)
        servingStation.showHistory(stations, selectedSimSlot, firstLocated)
    }

    private fun showHistoryPointCount() {
        val total = restoredHistory?.pointsBySlot?.values?.sumOf { it.size } ?: return
        val visible = visibleHistory?.pointsBySlot?.values?.sumOf { it.size } ?: 0
        showMapStatus(if (bandFilter.active) getString(R.string.map_band_filter_history_result, restoredSessionTitle, visible, total)
            else getString(R.string.map_recording_restored, restoredSessionTitle, total))
    }

    private fun updateCenterFabUi() {
        if (followMyLocation) {
            fabCenter.backgroundTintList =
                android.content.res.ColorStateList.valueOf(overlayColor(0xFF2E7D32.toInt()))
        } else {
            tintFabBlue(fabCenter)
        }
        applyFabIconUi(fabCenter)
    }

    private fun clearCurrentSession() {
        if (finishingDrive) return
        restoreJob?.cancel()
        sampler.clear()
        restoredHistory = null
        updateSignalPanel(latestSnapshots)
        val sid = currentSessionId ?: return
        val wasDriving = isDriving
        sampler.stop()
        finishingDrive = true
        scope.launch {
            try {
                sampler.awaitPersistence()
                stationWrites.awaitIdle()
                withContext(Dispatchers.IO) { dao.deleteRecording(sid) }
                linkedStationKeys.removeAll { it.startsWith("$sid|") }
                hasNewPointsInSession = false
                currentDistanceMeters = 0.0
                sessionCities.remove(sid)
                cityLookups.remove(sid)
                updateSignalPanel(latestSnapshots)
            } finally {
                finishingDrive = false
                if (wasDriving && isDriving) sampler.start()
            }
        }
    }

    private fun toggleDrive() {
        if (finishingDrive) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastToggleAt < 300) return
        lastToggleAt = now

        if (!isDriving) {
            pendingResumeSessionId = null
            pendingResumePoints = emptyList()
            requestDriveStart()
        } else {
            sampler.captureNow()
            isDriving = false
            syncMapMatchingState()
            sampler.stop()
            DriveKeepAliveService.stop(this)
            if (!mapResumed) locClient?.stopLocation()
            applyDriveUi(false)

            finishingDrive = true
            scope.launch {
                val pointError = sampler.awaitPersistence()
                val stationError = stationWrites.awaitIdle()
                val correctionError = correctionWrites.awaitIdle()
                val error = pointError ?: stationError ?: correctionError
                finishingDrive = false
                if (error != null) {
                    Logger.log("Drive persistence failed: ${error.message}")
                    showDriveMessage(R.string.map_drive_save_failed)
                }
                val sid = currentSessionId
                if (sid == null || !hasNewPointsInSession) {
                    currentSessionId = null
                    persistSessionId = null
                    hasNewPointsInSession = false
                } else {
                    currentSessionId = null
                    persistSessionId = null
                    hasNewPointsInSession = false
                    showMapStatus(getString(R.string.map_session_saved))
                }
                updateSignalPanel(displayedSnapshots())
            }
        }
    }

    private fun requestDriveStart() {
        if (isDriving || drivePermissionPending || isFinishing || isDestroyed) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) !=
            PackageManager.PERMISSION_GRANTED) {
            drivePermissionPending = true
            requestDrivePhone.launch(Manifest.permission.READ_PHONE_STATE)
            return
        }
        if (!hasLocPerm()) {
            drivePermissionPending = true
            requestDriveLocation.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ))
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            drivePermissionPending = true
            requestDriveNotification.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        startDriveRecording()
    }

    private fun startDriveRecording() {
        stationDistancePreview?.clear()
        // Location FGS must start from a visible Activity with location enabled.
        if (isDriving || isFinishing || isDestroyed ||
            !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        ) return
        if (!hasLocPerm() || !getSystemService(LocationManager::class.java).isLocationEnabled) {
            showDriveMessage(R.string.map_drive_location_required)
            return
        }
        try {
            DriveKeepAliveService.start(this)
            restoreJob?.cancel()
            restoredHistory = null
            sampler.clear()
            val sid = pendingResumeSessionId ?: newSessionId()
            if (pendingResumeSessionId != null) {
                sampler.renderHistory(pendingResumePoints, clearExisting = true)
            } else {
                currentDistanceMeters = 0.0
                sessionCities[sid] = lastCityName.takeIf {
                    val cityPoint = lastCityPoint
                    val startPoint = lastLatLng
                    cityPoint != null && startPoint != null &&
                        driveDistanceMeters(cityPoint.latitude, cityPoint.longitude, startPoint.latitude, startPoint.longitude) < 500
                }.orEmpty()
            }
            pendingResumeSessionId = null
            pendingResumePoints = emptyList()
            currentSessionId = sid
            persistSessionId = sid
            hasNewPointsInSession = false
            isDriving = true
            syncMapMatchingState()
            locClient?.startLocation()
            sampler.start()
            applyDriveUi(true)
            updateSignalPanel(latestSnapshots)
            if (::motionTracker.isInitialized) motionTracker.start()
            servingStation.update(latestSnapshots[selectedSimSlot])
            updateServingPosition()
            val notifications = NotificationManagerCompat.from(this)
            if (!notifications.areNotificationsEnabled() ||
                notifications.getNotificationChannel(DriveKeepAliveService.CHANNEL_ID)?.importance ==
                android.app.NotificationManager.IMPORTANCE_NONE
            ) showDriveMessage(R.string.map_drive_notification_disabled)
        } catch (error: Exception) {
            isDriving = false
            syncMapMatchingState()
            sampler.stop()
            DriveKeepAliveService.stop(this)
            currentSessionId = null
            persistSessionId = null
            applyDriveUi(false)
            Logger.log("Drive foreground service start failed: ${error.message}")
            showDriveMessage(R.string.map_drive_start_failed)
        }
    }

    private fun showDriveMessage(message: Int) {
        showMapStatus(getString(message))
    }

    private fun applyDriveUi(isDriving: Boolean) {
        val btn = fabDrive ?: return
        applyFabIconUi(btn)

        if (isDriving) {
            btn.setImageResource(android.R.drawable.ic_media_pause)
            btn.backgroundTintList =
                android.content.res.ColorStateList.valueOf(overlayColor(0xFFC62828.toInt()))
        } else {
            btn.setImageResource(android.R.drawable.ic_media_play)
            btn.backgroundTintList =
                android.content.res.ColorStateList.valueOf(overlayColor(fabBlue))
        }
    }

    /** Viewing the live map must never start a cloud recording. Background recording stays active. */
    private fun syncMapMatchingState() {
        mapMatching?.setActive(isDriving && restoredHistory == null && hasLocPerm())
    }

    private fun initMyLocation() {
        aMap.setLocationSource(this)
        updateDirectionDisplay()
        aMap.isMyLocationEnabled = true
        syncMapMatchingState()
        if (!isPickMode && ::motionTracker.isInitialized && mapResumed) motionTracker.start()
    }

    private fun updateDirectionDisplay() {
        val show = isPickMode || restoredHistory == null && !driveCourse.isMoving
        if (sdkDirectionVisible == show) return
        sdkDirectionVisible = show
        aMap.myLocationStyle = MyLocationStyle()
            .myLocationType(MyLocationStyle.LOCATION_TYPE_LOCATION_ROTATE_NO_CENTER)
            .showMyLocation(show).interval(1000L)
        locationMarker?.setVisible(restoredHistory == null && driveCourse.isMoving)
    }

    override fun activate(listener: LocationSource.OnLocationChangedListener?) {
        amapListener = listener

        if (locClient == null) {
            locClient = AMapLocationClient(applicationContext).apply {
                val opt = AMapLocationClientOption().apply {
                    locationMode = AMapLocationClientOption.AMapLocationMode.Hight_Accuracy
                    isOnceLocation = false
                    interval = 1000L
                    isNeedAddress = true
                    isWifiScan = true
                    isMockEnable = true
                }
                setLocationOption(opt)

                setLocationListener { loc ->
                    if (loc != null && loc.errorCode == 0) {
                        val now = SystemClock.elapsedRealtime()
                        val fixAt = driveFixElapsedMillis(loc.elapsedRealtimeNanos, loc.time, now, System.currentTimeMillis())
                        if (!driveValidPosition(loc.latitude, loc.longitude, loc.accuracy) || fixAt == null || (!isPickMode && !loc.hasAccuracy())) {
                            driveLog("SDK reject=invalid_coordinates_time_or_missing_accuracy type=${loc.locationType}")
                            return@setLocationListener
                        }
                        val coordinate = if (loc.coordType == AMapLocation.COORD_TYPE_WGS84)
                            Gcj02.wgs84ToGcj02(loc.latitude, loc.longitude) else loc.latitude to loc.longitude
                        val point = LatLng(coordinate.first, coordinate.second)
                        if (!isPickMode) {
                            // SAME_REQ/FIX_CACHE/LAST_LOCATION_CACHE are not fresh measurements.
                            if (loc.locationType in setOf(AMapLocation.LOCATION_TYPE_SAME_REQ,
                                    AMapLocation.LOCATION_TYPE_FIX_CACHE, AMapLocation.LOCATION_TYPE_LAST_LOCATION_CACHE)) {
                                driveLog("SDK reject=cached_location type=${loc.locationType}")
                                return@setLocationListener
                            }
                            // Observe first so a stale/jumping GPS fix cannot evict a valid IMU estimate.
                            val gpsValidated = if (loc.locationType == AMapLocation.LOCATION_TYPE_GPS && ::motionTracker.isInitialized) {
                                val wgs = if (loc.coordType == AMapLocation.COORD_TYPE_WGS84)
                                    loc.latitude to loc.longitude else Gcj02.gcj02ToWgs84(loc.latitude, loc.longitude)
                                runCatching {
                                    motionTracker.acceptGps(Location(loc).apply {
                                        latitude = wgs.first; longitude = wgs.second; elapsedRealtimeNanos = fixAt * 1_000_000
                                    })
                                }.onFailure { driveLog("IMU GPS observation failed: ${it.message}") }.getOrDefault(false)
                            } else false
                            val estimate = if (::motionTracker.isInitialized) motionTracker.engine.reading(now)
                                else DeadReckoningEngine.Reading(DeadReckoningEngine.Mode.NO_FIX)
                            val rejection = samplePositions.offerSdk(DriveSamplePosition(
                                point.latitude, point.longitude,
                                if (loc.locationType == AMapLocation.LOCATION_TYPE_GPS) "GPS" else "SDK", fixAt,
                                loc.accuracy, loc.latitude, loc.longitude, loc.coordType ?: "GCJ02", reason = "sdk"), now, estimate, gpsValidated)
                            if (rejection != null) {
                                driveLog("SDK reject=$rejection type=${loc.locationType} ageMs=${now - fixAt}")
                                return@setLocationListener
                            }
                        }
                        val city = loc.city?.takeIf { it.isNotBlank() } ?: loc.province.orEmpty()
                        if (city.isNotBlank()) { lastCityName = city; lastCityPoint = point }
                        lastAmapFixAt = fixAt
                        lastAmapIsGps = loc.locationType == AMapLocation.LOCATION_TYPE_GPS
                        lastAmapSpeed = if (lastAmapIsGps && loc.hasSpeed()) loc.speed.toDouble().takeIf { it in 0.0..75.0 } else null
                        // The SDK owns normal map positioning. IMU calibration must never veto a valid SDK fix.
                        acceptMapPosition(point, if (loc.hasBearing()) loc.bearing else null, false, loc.accuracy,
                            Triple(loc.latitude, loc.longitude, loc.coordType ?: "GCJ02"), lastAmapSpeed,
                            lastAmapIsGps && loc.hasSpeed() && (!loc.hasSpeedAccuracy() || loc.speedAccuracyMetersPerSecond <= 2f) &&
                                loc.hasBearing() && (!loc.hasBearingAccuracy() || loc.bearingAccuracyDegrees <= 25f), fixAt)
                        lastPositionAt = fixAt
                        if (mapResumed) amapListener?.onLocationChanged(if (loc.coordType == AMapLocation.COORD_TYPE_WGS84)
                            Location(loc).apply { latitude = point.latitude; longitude = point.longitude } else loc)
                        val sid = persistSessionId
                        if (sid != null && city.isNotBlank() && sessionCities[sid].isNullOrBlank()) {
                            // Only use an address associated with the first saved point, not a later city.
                            DrivePersistence.scope.launch {
                                val first = dao.getFirstPoint(sid)
                                if (first?.lat != null && first.lng != null && driveDistanceMeters(first.lat, first.lng, point.latitude, point.longitude) < 500) {
                                    sessionCities[sid] = city
                                    dao.setCityIfEmpty(sid, city)
                                }
                            }
                        }
                    }
                }
            }
        }
        locClient?.startLocation()
    }

    override fun deactivate() {
        amapListener = null
        if (!isDriving) locClient?.stopLocation()
    }

    override fun onResume() {
        super.onResume()
        mapResumed = true
        mapView.onResume()
        applyMapTypeForCurrentMode()
        updateMapTypeFabUi()
        if (!isPickMode) {
            startWatchCellChanges()
            syncMapMatchingState()
            restoredHistory?.let { mapMatching?.showHistory(it.pointsBySlot[selectedSimSlot].orEmpty()) }
            if (hasLocPerm()) motionTracker.start()
            if (hasLocPerm()) locClient?.startLocation()
            updateServingPosition()
            if (restoredHistory == null) servingStation.update(latestSnapshots[selectedSimSlot])
        }
    }

    override fun onPause() {
        super.onPause()
        mapResumed = false
        locationMarker?.pause()
        mapView.onPause()
        if (!isDriving) mapMatching?.setActive(false)
        // Keep per-SIM telemetry fresh while a running road test is backgrounded.
        if (isDriving) locClient?.startLocation()
        else if (!isPickMode && ::motionTracker.isInitialized) {
            motionTracker.stop()
            locClient?.stopLocation()
        }
    }

    override fun onDestroy() {
        stationDistancePreview?.clear()
        locationMarker?.close()
        mapMatching?.close()
        if (!isPickMode) {
            if (::sampler.isInitialized) {
                sampler.clearSelection()
                if (isDriving) sampler.captureNow()
                sampler.stop()
            }
            isDriving = false
            DriveKeepAliveService.stop(this)
            if (::motionTracker.isInitialized) motionTracker.stop()
            if (::servingStation.isInitialized) servingStation.clear()
            probeAntennas.clear()
            if (::probeAntennaLte.isInitialized) { probeAntennaLte.clear(); probeAntennaNr.clear() }
        }
        // Already queued batches belong to DrivePersistence, not the page scope.
        // Committed points survive back navigation, task removal and process restart.
        scope.cancel()
        mapView.onDestroy()
        locClient?.onDestroy()
        locClient = null
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView.onSaveInstanceState(outState)
    }

    private val recordingManager = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val sid = result.data?.getStringExtra("session_id")
        if (result.resultCode == android.app.Activity.RESULT_OK && sid != null) {
            if (result.data?.getBooleanExtra("resume", false) == true) prepareResumeSession(sid)
            else restoreSessionPoints(sid)
        } else {
            val restored = restoredSessionId
            if (restoredHistory != null && restored != null) scope.launch {
                if (withContext(Dispatchers.IO) { dao.getBySession(restored).isEmpty() }) {
                    sampler.clear(); restoredHistory = null; restoredStations = emptyList(); restoredSessionId = null
                    servingStation.clear(); servingStation.update(latestSnapshots[selectedSimSlot]); updateServingPosition()
                    updateSignalPanel(latestSnapshots)
                }
            }
        }
    }

    private fun showSessionRestoreDialog() {
        if (isDriving || finishingDrive) { showDriveMessage(R.string.map_history_stop_recording); return }
        recordingManager.launch(com.nvmex.networkhelper.ui.management.DataManagementActivity.intent(this, "drive"))
    }

    private suspend fun loadSessionMetadata(sid: String): DriveSessionEntity {
        dao.getSession(sid)?.let { return it }
        val rows = dao.getBySession(sid)
        val distance = driveTrackLength(rows.map { DriveTrackFix(it.ts, it.lat, it.lng, it.segmentStart) })
        return DriveSessionEntity(sid, distanceMeters = distance).also { dao.putSession(it) }
    }

    private fun prepareResumeSession(sid: String) {
        if (isDriving || finishingDrive || drivePermissionPending) return
        restoreJob?.cancel()
        restoreJob = scope.launch {
            val (rows, meta) = withContext(Dispatchers.IO) { dao.getBySession(sid) to loadSessionMetadata(sid) }
            val points = rows.mapNotNull { it.toDrivePointOrNull() }
            if (points.isEmpty()) { showMapStatus(getString(R.string.map_sessions_empty)); return@launch }
            pendingResumeSessionId = sid
            pendingResumePoints = points
            sessionCities[sid] = meta.cityName
            currentDistanceMeters = meta.distanceMeters
            requestDriveStart()
        }
    }

    private fun formatDistance(meters: Double): String = if (meters < 1000)
        String.format(Locale.getDefault(), "%.0f m", meters) else String.format(Locale.getDefault(), "%.2f km", meters / 1000)

    private fun resolveSessionCity(sid: String, firstPoint: LatLng) {
        if (!cityLookups.add(sid) || !sessionCities[sid].isNullOrBlank()) return
        val context = applicationContext
        DrivePersistence.scope.launch {
            val city = runCatching {
                val original = dao.getFirstPoint(sid)
                val (lat, lng) = Gcj02.gcj02ToWgs84(original?.lat ?: firstPoint.latitude, original?.lng ?: firstPoint.longitude)
                @Suppress("DEPRECATION")
                android.location.Geocoder(context, Locale.getDefault()).getFromLocation(lat, lng, 1)
                    ?.firstOrNull()?.let { it.locality?.takeIf { name -> name.isNotBlank() } ?: it.adminArea }
            }.getOrNull()?.takeIf { it.isNotBlank() } ?: return@launch
            sessionCities[sid] = city
            dao.setCityIfEmpty(sid, city)
        }
    }

    private fun restoreSessionPoints(sessionId: String) {
        if (isDriving || finishingDrive) return
        restoreJob?.cancel()
        restoreJob = scope.launch {
            val rows = withContext(Dispatchers.IO) {
                runCatching { dao.getBySession(sessionId) }.getOrDefault(emptyList())
            }
            val stations = withContext(Dispatchers.IO) { dao.getStations(sessionId) }
            if (rows.isEmpty()) {
                showMapStatus("Session 无数据：$sessionId")
                return@launch
            }

            val points = rows.mapNotNull { it.toDrivePointOrNull() }
            if (points.isEmpty()) {
                showMapStatus("Session 数据解析失败：$sessionId")
                return@launch
            }

            logRestoreFieldStats(sessionId = sessionId, points = points)
            if (isDriving || finishingDrive) return@launch
            val history = DriveHistory(points)
            restoredHistory = history
            restoredSessionId = sessionId
            val metadata = withContext(Dispatchers.IO) { loadSessionMetadata(sessionId) }
            sessionCities[sessionId] = metadata.cityName
            sessionDisplayNames[sessionId] = "${metadata.cityName.ifBlank { getString(R.string.map_unknown_city) }} · ${formatTs(points.minOf { it.ts })}"
            restoredStations = stations
            restoredSessionTitle = sessionDisplayNames[sessionId].orEmpty()
            selectedSimSlot = visibleHistory?.initialSlot(selectedSimSlot) ?: history.initialSlot(selectedSimSlot)
            sampler.renderHistory(points, clearExisting = true)
            sampler.setVisibleSimSlot(selectedSimSlot)
            updateSignalPanel(displayedSnapshots())
            followMyLocation = false
            shouldSkipFirstFixCenter = true
            updateCenterFabUi()
            frameCurrentTrack(historyStartView)
            refreshHistoryStation()
            Logger.log("Drive restore session=$sessionId loaded=${points.size}")
            showHistoryPointCount()
        }
    }

    private fun showDeleteSessionConfirm(sessionId: String, onDeleted: () -> Unit) {
        if (isDriving && currentSessionId == sessionId) {
            showMapStatus("当前 Session 正在采样，请先停止")
            return
        }

        newDialogBuilder()
            .setTitle(R.string.map_delete_session)
            .setMessage(getString(R.string.map_delete_recording_confirm, sessionDisplayNames[sessionId].orEmpty()))
            .setPositiveButton("删除") { _, _ ->
                scope.launch {
                    stationWrites.awaitIdle()
                    withContext(Dispatchers.IO) {
                        runCatching { dao.deleteRecording(sessionId) }
                    }
                    sampler.clear()
                    restoredHistory = null
                    restoredStations = emptyList()
                    servingStation.clear()
                    servingStation.update(latestSnapshots[selectedSimSlot])
                    updateServingPosition()
                    updateSignalPanel(latestSnapshots)
                    showMapStatus(getString(R.string.map_recording_deleted))
                    onDeleted()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun startWatchCellChanges() {
        if (cellWatchJob?.isActive == true) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) !=
            PackageManager.PERMISSION_GRANTED) return

        cellWatchJob = scope.launch {
            panelRepository.observeStates().collect { bySubId ->
                val subscriptions = runCatching { multiSnapshotter.getActiveSims() }
                    .getOrDefault(emptyList())
                    .map { it.subscriptionId to it.simSlotIndex }
                val states = driveSnapshotsBySlot(bySubId, subscriptions)
                latestSnapshots = states
                if (restoredHistory == null) {
                    updateSignalPanel(states)
                    if (mapResumed) {
                        updateServingPosition()
                        servingStation.update(states[selectedSimSlot])
                    }
                }
            }
        }
    }

    private fun triggerOverlayForState(state: NetworkPanelUiState): Boolean {
        if (!::servingStation.isInitialized) return false
        updateServingPosition()
        servingStation.update(state)
        return true
    }

    private fun hasLocPerm(): Boolean {
        return ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
    }

    private fun dp(v: Int): Int = (density * v + 0.5f).toInt()

    private fun tintFabBlue(fab: FloatingActionButton) {
        fab.backgroundTintList =
            android.content.res.ColorStateList.valueOf(overlayColor(fabBlue))
    }

    private fun newDialogBuilder(): AlertDialog.Builder {
        return AlertDialog.Builder(this)
    }

    private fun applyFabIconUi(fab: FloatingActionButton) {
        fab.imageTintList = fabIconWhite
        fab.imageAlpha = 255
    }

    private fun applyFabDefaultUi(fab: FloatingActionButton) {
        tintFabBlue(fab)
        applyFabIconUi(fab)
        fab.compatElevation = dp(3).toFloat()
    }

    private fun normalizeSectorDrawConfig(angleDeg: Float, radiusMeters: Double): SectorDrawConfig {
        return SectorDrawConfig(
            angleDeg = angleDeg.coerceIn(20f, 180f),
            radiusMeters = radiusMeters.coerceIn(50.0, 2000.0)
        )
    }

    private fun normalizeMarkerSampleIntervalSec(seconds: Long): Long {
        return seconds.coerceIn(MIN_MARKER_SAMPLE_INTERVAL_SEC, MAX_MARKER_SAMPLE_INTERVAL_SEC)
    }

    private fun loadSectorDrawConfig(): SectorDrawConfig {
        val angle = overlayPrefs.getFloat(KEY_SECTOR_ANGLE, DEFAULT_SECTOR_ANGLE_DEG)
        val radius = overlayPrefs.getFloat(KEY_SECTOR_RADIUS, DEFAULT_SECTOR_RADIUS_METERS.toFloat()).toDouble()
        return normalizeSectorDrawConfig(angle, radius)
    }

    private fun saveSectorDrawConfig(config: SectorDrawConfig) {
        overlayPrefs.edit()
            .putFloat(KEY_SECTOR_ANGLE, config.angleDeg)
            .putFloat(KEY_SECTOR_RADIUS, config.radiusMeters.toFloat())
            .apply()
    }

    private fun loadMarkerSampleIntervalSec(): Long {
        val saved = overlayPrefs.getLong(KEY_MARKER_SAMPLE_INTERVAL_SEC, DEFAULT_MARKER_SAMPLE_INTERVAL_SEC)
        return normalizeMarkerSampleIntervalSec(saved)
    }

    private fun saveMarkerSampleIntervalSec(seconds: Long) {
        overlayPrefs.edit()
            .putLong(KEY_MARKER_SAMPLE_INTERVAL_SEC, normalizeMarkerSampleIntervalSec(seconds))
            .apply()
    }

    private fun applySectorDrawConfigToOverlays() {
        if (::lteOverlay.isInitialized) {
            lteOverlay.updateSectorDrawConfig(
                angleDeg = sectorDrawConfig.angleDeg,
                radiusMeters = sectorDrawConfig.radiusMeters
            )
        }
        if (::nrOverlay.isInitialized) {
            nrOverlay.updateSectorDrawConfig(
                angleDeg = sectorDrawConfig.angleDeg,
                radiusMeters = sectorDrawConfig.radiusMeters
            )
        }
        if (::nearbyLteOverlay.isInitialized) {
            nearbyLteOverlay.updateSectorDrawConfig(sectorDrawConfig.angleDeg, sectorDrawConfig.radiusMeters)
            nearbyNrOverlay.updateSectorDrawConfig(sectorDrawConfig.angleDeg, sectorDrawConfig.radiusMeters)
            renderNearbyResult()
        }
        if (::probeAntennaLte.isInitialized) {
            probeAntennaLte.updateSectorDrawConfig(sectorDrawConfig.angleDeg, sectorDrawConfig.radiusMeters)
            probeAntennaNr.updateSectorDrawConfig(sectorDrawConfig.angleDeg, sectorDrawConfig.radiusMeters)
            renderProbeAntennas()
        }
    }

    private fun showSectorConfigDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        fun section(title: Int) = TextView(this).apply {
            setText(title)
            textSize = 15f
            setTextColor(Color.DKGRAY)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(12), 0, dp(6))
        }
        container.addView(section(R.string.map_sector_settings))

        val angleEdit = TextInputEditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText("%.0f".format(sectorDrawConfig.angleDeg))
        }
        val angleLayout = TextInputLayout(this).apply {
            hint = "扇区角度 (20-180)"
            addView(angleEdit)
        }

        val distanceEdit = TextInputEditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText("%.0f".format(sectorDrawConfig.radiusMeters))
        }
        val distanceLayout = TextInputLayout(this).apply {
            hint = "扇区距离米数 (50-2000)"
            addView(distanceEdit)
        }

        val sampleIntervalEdit = TextInputEditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(markerSampleIntervalSec.toString())
        }
        val sampleIntervalLayout = TextInputLayout(this).apply {
            hint = "打点间隔秒数 (${MIN_MARKER_SAMPLE_INTERVAL_SEC}-${MAX_MARKER_SAMPLE_INTERVAL_SEC})"
            addView(sampleIntervalEdit)
        }

        container.addView(angleLayout)
        container.addView(distanceLayout)
        container.addView(sampleIntervalLayout)

        container.addView(section(R.string.map_display_options))
        fun displaySwitch(label: Int, value: Boolean) = SwitchMaterial(this).apply {
            setText(label)
            isChecked = value
            container.addView(this)
        }
        val handoverSwitch = displaySwitch(R.string.map_show_handovers, showHandoverPoints)
        val stationMarkersSwitch = displaySwitch(R.string.map_show_station_markers, showStationMarkers)
        val antennasSwitch = displaySwitch(R.string.map_show_serving_antennas, showServingAntennas)
        val linkSwitch = displaySwitch(R.string.map_show_serving_link, showServingLink)
        val inertialSwitch = displaySwitch(R.string.map_inertial_enable, inertialEnabled)
        val inertialMinutes = TextInputEditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText((motionTracker.engine.maxOutageMs / 60_000).toString())
        }
        val inertialMinutesLayout = TextInputLayout(this).apply {
            setHint(R.string.map_inertial_max_minutes)
            addView(inertialMinutes)
        }
        container.addView(inertialMinutesLayout)
        container.addView(TextView(this).apply {
            setText(R.string.map_inertial_description)
            textSize = 12f
            setPadding(0, dp(4), 0, dp(8))
        })
        displaySwitch(R.string.map_matching_title, mapMatching?.enabled == true).apply {
            setOnCheckedChangeListener { _, checked -> mapMatching?.setEnabled(checked) }
        }
        container.addView(TextView(this).apply {
            text = getString(R.string.map_matching_description) + "\n" + getString(R.string.map_matching_coverage)
            textSize = 12f; setPadding(0, dp(4), 0, dp(8))
        })

        container.addView(section(R.string.map_nearby_settings))
        val enableProbe = SwitchMaterial(this).apply {
            setText(R.string.map_nearby_enable)
            isChecked = nearbyEnabled
        }
        container.addView(enableProbe)
        val radiusLabel = TextView(this).apply {
            text = getString(R.string.map_nearby_radius, nearbyRadiusKm)
        }
        container.addView(radiusLabel)
        val radiusSlider = SeekBar(this).apply {
            max = 9
            progress = nearbyRadiusKm - 1
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    radiusLabel.text = getString(R.string.map_nearby_radius, progress + 1)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        container.addView(radiusSlider)
        container.addView(TextView(this).apply { setText(R.string.map_nearby_rat_hint) })
        val ratSelector = Spinner(this).apply {
            adapter = ArrayAdapter(this@AmapMapActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf(getString(R.string.map_nearby_rat_all), "4G (LTE)", "5G (NR)"))
            setSelection(when (nearbyRat) { "LTE" -> 1; "NR" -> 2; else -> 0 })
        }
        container.addView(ratSelector, LinearLayout.LayoutParams(-1, dp(48)))
        val bandEdit = TextInputEditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
            setText(nearbyBand.orEmpty())
        }
        val bandLayout = TextInputLayout(this).apply {
            hint = getString(R.string.map_nearby_band_hint)
            helperText = getString(R.string.map_nearby_band_description)
            addView(bandEdit)
        }
        container.addView(bandLayout)
        container.addView(TextView(this).apply {
            setText(R.string.map_nearby_description)
            textSize = 12f
            setPadding(0, dp(4), 0, dp(12))
        })

        val dialog = newDialogBuilder()
            .setTitle(R.string.map_drawing_probe_settings)
            .setView(ScrollView(this).apply { addView(container) })
            .setPositiveButton(R.string.map_parameters_save, null)
            .setNeutralButton(R.string.map_nearby_probe_now, null)
            .setNegativeButton(android.R.string.cancel, null)
            .show()

        fun applySettings(): Boolean {
                val angle = angleEdit.text?.toString()?.trim()?.toFloatOrNull()
                val radius = distanceEdit.text?.toString()?.trim()?.toDoubleOrNull()
                val sampleIntervalSecInput = sampleIntervalEdit.text?.toString()?.trim()?.toLongOrNull()
                val outageMinutes = inertialMinutes.text?.toString()?.trim()?.toIntOrNull()
                inertialMinutesLayout.error = if (outageMinutes == null || outageMinutes !in 1..30)
                    getString(R.string.map_parameter_invalid) else null
                val bandInput = bandEdit.text?.toString()?.trim().orEmpty()
                val rat = when (ratSelector.selectedItemPosition) { 1 -> "LTE"; 2 -> "NR"; else -> null }
                val parsedBands = runCatching { nearbyProbeBands(bandInput, rat) }
                bandLayout.error = parsedBands.exceptionOrNull()?.let {
                    getString(if (it.message == "band_rat_mismatch") R.string.map_nearby_band_rat_mismatch
                        else R.string.map_nearby_band_invalid)
                }
                angleLayout.error = if (angle == null || !angle.isFinite() || angle !in 20f..180f)
                    getString(R.string.map_parameter_invalid) else null
                distanceLayout.error = if (radius == null || !radius.isFinite() || radius !in 50.0..2000.0)
                    getString(R.string.map_parameter_invalid) else null
                sampleIntervalLayout.error = if (sampleIntervalSecInput == null ||
                    sampleIntervalSecInput !in MIN_MARKER_SAMPLE_INTERVAL_SEC..MAX_MARKER_SAMPLE_INTERVAL_SEC)
                    getString(R.string.map_parameter_invalid) else null
                if (angleLayout.error != null || distanceLayout.error != null || sampleIntervalLayout.error != null ||
                    bandLayout.error != null || inertialMinutesLayout.error != null)
                    return false

                sectorDrawConfig = normalizeSectorDrawConfig(angle!!, radius!!)
                showHandoverPoints = handoverSwitch.isChecked
                showStationMarkers = stationMarkersSwitch.isChecked
                showServingAntennas = antennasSwitch.isChecked
                showServingLink = linkSwitch.isChecked
                inertialEnabled = inertialSwitch.isChecked
                overlayPrefs.edit().putBoolean("show_handover_points", showHandoverPoints)
                    .putBoolean("show_station_markers", showStationMarkers)
                    .putBoolean("show_serving_antennas", showServingAntennas)
                    .putBoolean("show_serving_link", showServingLink)
                    .putBoolean("inertial_auto_enabled", inertialEnabled).apply()
                sampler.setHandoverVisible(showHandoverPoints)
                applyStationMarkerVisibility()
                motionTracker.setInertialEnabled(inertialEnabled)
                motionTracker.engine.maxOutageMs = outageMinutes!! * 60_000L
                overlayPrefs.edit().putInt("inertial_max_minutes", outageMinutes).apply()
                servingStation.setDisplay(showServingAntennas, showServingLink)
                markerSampleIntervalSec = normalizeMarkerSampleIntervalSec(sampleIntervalSecInput!!)
                saveSectorDrawConfig(sectorDrawConfig)
                saveMarkerSampleIntervalSec(markerSampleIntervalSec)
                val band = parsedBands.getOrThrow().filterNotNull().joinToString(",").takeIf { it.isNotEmpty() }
                val probeChanged = nearbyEnabled != enableProbe.isChecked ||
                    nearbyRadiusKm != radiusSlider.progress + 1 || nearbyBand != band || nearbyRat != rat
                nearbyEnabled = enableProbe.isChecked
                nearbyRadiusKm = radiusSlider.progress + 1
                nearbyBand = band
                nearbyRat = rat
                overlayPrefs.edit().putBoolean(KEY_NEARBY_ENABLED, nearbyEnabled)
                    .putInt(KEY_NEARBY_RADIUS_KM, nearbyRadiusKm).putString(KEY_NEARBY_BAND, band)
                    .putString(KEY_NEARBY_RAT, rat).apply()
                if (probeChanged || !nearbyEnabled) clearNearbyCells()
                sampler.updateIntervalMs(markerSampleIntervalSec * 1000L)
                applySectorDrawConfigToOverlays()

                servingStation.clear()
                        displayedSnapshots()[selectedSimSlot]?.let { triggerOverlayForState(it) }
                return true
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (applySettings()) {
                dialog.dismiss()
                probeNearbyCells()
            }
        }
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            if (applySettings()) {
                dialog.dismiss()
                probeNearbyCells(force = true)
            }
        }
    }

    private fun showStationExclusionDialog() {
        val selected = stationExclusion.bands.toMutableSet()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(8))
        }
        container.addView(TextView(this).apply {
            setText(R.string.map_station_exclusion_description)
            textSize = 12f
            setPadding(0, 0, 0, dp(8))
        })
        StationExclusion.selectableBands.chunked(3).forEach { bands ->
            val row = LinearLayout(this)
            bands.forEach { band ->
                row.addView(androidx.appcompat.widget.AppCompatCheckBox(this).apply {
                    text = "B$band"
                    isChecked = band in selected
                    setOnCheckedChangeListener { _, checked -> if (checked) selected.add(band) else selected.remove(band) }
                }, LinearLayout.LayoutParams(0, dp(48), 1f))
            }
            container.addView(row)
        }
        val channels = TextInputEditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            maxLines = 3
            setText(stationExclusion.channelInput())
        }
        val field = TextInputLayout(this).apply {
            setHint(R.string.map_station_exclusion_channels)
            helperText = getString(R.string.map_station_exclusion_channels_hint)
            addView(channels)
        }
        container.addView(field)
        val dialog = newDialogBuilder().setTitle(R.string.map_station_exclusion_title)
            .setView(ScrollView(this).apply { addView(container) })
            .setPositiveButton(R.string.map_station_exclusion_apply, null)
            .setNeutralButton(R.string.map_station_exclusion_clear) { _, _ -> applyStationExclusion(StationExclusion()) }
            .setNegativeButton(android.R.string.cancel, null).show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val parsed = runCatching { StationExclusion.parseChannels(channels.text?.toString().orEmpty()) }
            if (parsed.isFailure) field.error = getString(R.string.map_station_exclusion_invalid)
            else {
                applyStationExclusion(StationExclusion(selected.toSet(), parsed.getOrThrow()))
                dialog.dismiss()
            }
        }
    }

    private fun applyStationExclusion(value: StationExclusion) {
        if (stationExclusion == value) return
        stationExclusion = value
        overlayPrefs.edit().putString(KEY_STATION_EXCLUDED_BANDS, value.bandInput())
            .putString(KEY_STATION_EXCLUDED_CHANNELS, value.channelInput()).apply()
        sampler.clearSelection()
        servingStation.setExclusion(value)
        renderProbeAntennas()
        renderNearbyResult()
    }

    private fun renderNearbyResult() {
        if (!::nearbyLteOverlay.isInitialized || !::nearbyStatus.isInitialized) return
        val result = nearbyResult
        val summary = if (stationExclusion.active) getString(R.string.map_station_exclusion_summary, stationExclusion.summary()) else ""
        val message = if (result == null) summary else {
            val visibleLte = result.lte.filterNot { stationExclusion.excludes(it) }
            val lteCount = nearbyLteOverlay.renderNearbyCells(visibleLte.take(1000))
            val nrCount = nearbyNrOverlay.renderNearbyCells(result.nr.take(1000))
            // Nearby refresh replaces SDK Marker objects. Restore the same station by key
            // before a camera refresh notices the removed marker and clears the preview.
            sampler.refreshSelectedStationMarker()
            getString(R.string.map_nearby_result, nearbyRadiusKm, lteCount, nrCount) +
                (nearbyRat?.let { if (it == "LTE") " · 4G" else " · 5G" } ?: "") +
                (nearbyBand?.let { " · $it" } ?: "") +
                (if (result.truncated || visibleLte.size > 1000 || result.nr.size > 1000) getString(R.string.map_nearby_truncated) else "") +
                (if (nearbyPartialFailure) getString(R.string.map_nearby_partial_failure) else "") +
                (if (summary.isNotEmpty()) "\n$summary" else "")
        }
        nearbyStatus.setSingleLine(false)
        nearbyStatus.maxLines = 3
        nearbyStatus.text = message
        nearbyStatus.contentDescription = message
        nearbyStatus.visibility = if (message.isEmpty()) View.GONE else View.VISIBLE
    }

    private data class NearbyAttempt(val result: NearbyCellsResponse?, val error: String? = null)

    private fun clearNearbyCells() {
        nearbyJob?.cancel()
        nearbyJob = null
        lastNearbyAt = 0L
        lastNearbyCenter = null
        nearbyResult = null
        nearbyPartialFailure = false
        sampler.clearSelection()
        nearbyLteOverlay.clear()
        nearbyNrOverlay.clear()
        nearbyRangeCircle?.remove()
        nearbyRangeCircle = null
        renderNearbyResult()
    }

    private fun probeNearbyCells(force: Boolean = false) {
        if ((!nearbyEnabled && !force) || !mapResumed) return
        val center = lastLatLng ?: run {
            if (force) showDriveMessage(R.string.map_nearby_location_pending)
            return
        }
        if (nearbyJob?.isActive == true) {
            if (!force) return
            nearbyJob?.cancel()
        }
        val now = SystemClock.elapsedRealtime()
        val elapsed = now - lastNearbyAt
        val moved = lastNearbyCenter?.let {
            com.amap.api.maps.AMapUtils.calculateLineDistance(it, center).toDouble()
        } ?: Double.MAX_VALUE
        if (!force && lastNearbyAt != 0L &&
            (elapsed < 30_000L || elapsed < 60_000L && moved < maxOf(250.0, nearbyRadiusKm * 250.0))) return
        lastNearbyAt = now
        val radius = nearbyRadiusKm
        val band = nearbyBand
        val rat = nearbyRat
        nearbyStatus.visibility = View.VISIBLE
        nearbyStatus.setText(R.string.map_nearby_loading)
        nearbyJob = scope.launch {
            try {
                val (latitude, longitude) = Gcj02.gcj02ToWgs84(center.latitude, center.longitude)
                val queries = nearbyProbeBands(band, rat)
                val attempts = mutableListOf<NearbyAttempt>()
                for (batch in queries.chunked(3)) {
                    attempts += coroutineScope {
                        batch.map { requestedBand -> async {
                            try {
                                val response = apiService.queryNearbyCells(NearbyCellsRequest(
                                    latitude, longitude, radius, band = requestedBand, rat = rat))
                                val body = response.body()
                                if (response.isSuccessful && body?.ok == true) NearbyAttempt(body)
                                else NearbyAttempt(null, response.errorBody()?.string()?.let {
                                    runCatching { org.json.JSONObject(it).optString("error") }.getOrNull()
                                })
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { NearbyAttempt(null) }
                        } }.awaitAll()
                    }
                    nearbyStatus.text = getString(R.string.map_nearby_loading_progress, attempts.size, queries.size)
                }
                val responses = attempts.mapNotNull { it.result }
                if (responses.isEmpty()) {
                    nearbyStatus.text = when (attempts.firstOrNull()?.error) {
                        "invalid_band" -> getString(R.string.map_nearby_band_unsupported, band.orEmpty())
                        "band_rat_mismatch" -> getString(R.string.map_nearby_band_rat_mismatch)
                        else -> getString(R.string.map_nearby_failed)
                    }
                    return@launch
                }
                nearbyResult = mergeNearbyProbeResults(responses, latitude, longitude)
                nearbyPartialFailure = attempts.any { it.result == null }
                lastNearbyCenter = center
                nearbyRangeCircle?.remove()
                nearbyRangeCircle = aMap.addCircle(com.amap.api.maps.model.CircleOptions()
                    .center(center).radius(radius * 1000.0)
                    .strokeColor(0x88448AFF.toInt()).strokeWidth(2f).fillColor(0x08448AFF))
                renderNearbyResult()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Logger.log("Nearby query failed: ${error.message}")
                nearbyStatus.setText(R.string.map_nearby_failed)
            }
        }
    }

    private fun newSessionId(): String {
        return "S${System.currentTimeMillis()}_${kotlin.random.Random.nextInt(1000, 9999)}"
    }

    private fun logRestoreFieldStats(sessionId: String, points: List<DrivePoint>) {
        if (points.isEmpty()) return
        fun ok(v: String?): Boolean {
            val s = v?.trim()
            return !s.isNullOrEmpty() && s != "-"
        }
        val total = points.size
        val tac = points.count { ok(it.snapshot.tac) }
        val pci = points.count { ok(it.snapshot.pci) }
        val ci = points.count { ok(it.snapshot.ci) }
        val arfcn = points.count { ok(it.snapshot.arfcn) }
        val band = points.count { ok(it.snapshot.band) }
        Logger.log(
            "Drive restore stats session=$sessionId total=$total " +
                    "tac=$tac pci=$pci ci=$ci arfcn=$arfcn band=$band"
        )
    }

    private fun formatTs(ts: Long): String = runCatching {
        sessionTimeFormatter.format(Date(ts))
    }.getOrElse { ts.toString() }

    private var initialTriangleTriggered = false

    private fun triggerTriangleOnceOnEnter() {
        if (initialTriangleTriggered) return
        initialTriangleTriggered = true

        scope.launch {
            repeat(12) {
                if (restoredHistory != null) return@launch
                val states = snapshotAllSims()
                if (states[selectedSimSlot]?.let { triggerOverlayForState(it) } == true) {
                    return@launch
                }
                kotlinx.coroutines.delay(500)
            }
        }
    }



    private fun toggle2D3D() {
        is3DMode = !is3DMode
        overlayPrefs.edit().putBoolean("map_3d", is3DMode).apply()
        update2D3DFabUi()

        val pos = aMap.cameraPosition
        val targetTilt = if (is3DMode) followTilt3D else 0f
        val targetBearing = if (is3DMode) (lastBearingDeg ?: pos.bearing) else 0f

        val cam = com.amap.api.maps.model.CameraPosition(
            pos.target,
            pos.zoom,
            targetTilt,
            targetBearing
        )
        ignoreCameraMoveCancelOnce = true
        animateMapControl(CameraUpdateFactory.newCameraPosition(cam))
    }

    private fun update2D3DFabUi() {
        if (!::viewModeButton.isInitialized) return
        viewModeButton.text = if (is3DMode) "3D" else "2D"
        viewModeButton.contentDescription = getString(R.string.map_control_view_mode, viewModeButton.text)
    }

    private fun toggleBaseMapMode() {
        baseMapMode = if (baseMapMode == BaseMapMode.STANDARD) {
            BaseMapMode.SATELLITE
        } else {
            BaseMapMode.STANDARD
        }
        saveBaseMapMode(baseMapMode)
        applyMapTypeForCurrentMode()
        updateMapTypeFabUi()
    }

    private fun applyMapTypeForCurrentMode() {
        val targetMapType = when (baseMapMode) {
            BaseMapMode.SATELLITE -> AMap.MAP_TYPE_SATELLITE
            BaseMapMode.STANDARD -> if (isSystemNightMode()) {
                AMap.MAP_TYPE_NIGHT
            } else {
                AMap.MAP_TYPE_NORMAL
            }
        }
        aMap.uiSettings.isZoomControlsEnabled = false
        if (aMap.mapType != targetMapType) {
            aMap.mapType = targetMapType
        }
    }

    private fun updateMapTypeFabUi() {
        if (!::mapTypeButton.isInitialized) return
        mapTypeButton.setText(if (baseMapMode == BaseMapMode.SATELLITE) R.string.map_satellite else R.string.map_standard)
        mapTypeButton.contentDescription = getString(R.string.map_control_map_type, mapTypeButton.text)
    }

    private fun isSystemNightMode(): Boolean {
        val mode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return mode == Configuration.UI_MODE_NIGHT_YES
    }

    private fun loadBaseMapMode(): BaseMapMode {
        return when (overlayPrefs.getString(KEY_BASE_MAP_MODE, BaseMapMode.STANDARD.value)) {
            BaseMapMode.SATELLITE.value -> BaseMapMode.SATELLITE
            else -> BaseMapMode.STANDARD
        }
    }

    private fun saveBaseMapMode(mode: BaseMapMode) {
        overlayPrefs.edit().putString(KEY_BASE_MAP_MODE, mode.value).apply()
    }

    private fun applyFollowCamera(latLng: LatLng, bearing: Float?) {
        followCameraFrame(locationMarker?.displayedPosition() ?: latLng, bearing)
    }

    private fun followCameraFrame(latLng: LatLng, bearing: Float?) {
        val pos = aMap.cameraPosition
        val zoom = pos.zoom
        val tilt = if (is3DMode) followTilt3D else 0f
        val b = if (is3DMode) (bearing ?: pos.bearing) else 0f

        val cam = com.amap.api.maps.model.CameraPosition(
            latLng,
            zoom,
            tilt,
            b
        )
        ignoreCameraMoveCancelOnce = true
        aMap.moveCamera(CameraUpdateFactory.newCameraPosition(cam))
    }

    private fun animateMapControl(update: com.amap.api.maps.CameraUpdate) {
        cameraControlAnimating = true
        aMap.animateCamera(update, 250L, object : AMap.CancelableCallback {
            override fun onFinish() { cameraControlAnimating = false }
            override fun onCancel() { cameraControlAnimating = false }
        })
    }

    private data class SectorDrawConfig(
        val angleDeg: Float,
        val radiusMeters: Double
    )

    private enum class BaseMapMode(val value: String) {
        STANDARD("standard"),
        SATELLITE("satellite")
    }

    companion object {
        private const val MAX_SESSION_LIST = 80
        private const val PREF_MAP_OVERLAY = "map_overlay_prefs"
        private const val KEY_SECTOR_ANGLE = "sector_angle_deg"
        private const val KEY_POINT_LTE_BANDS = "point_lte_bands"
        private const val KEY_POINT_NR_BANDS = "point_nr_bands"
        private const val KEY_SHOW_BAND_COVERAGE = "show_band_coverage"
        private const val KEY_SECTOR_RADIUS = "sector_radius_meters"
        private const val KEY_MARKER_SAMPLE_INTERVAL_SEC = "marker_sample_interval_sec"
        private const val KEY_BASE_MAP_MODE = "base_map_mode"
        private const val KEY_NEARBY_ENABLED = "nearby_enabled"
        private const val KEY_NEARBY_RADIUS_KM = "nearby_radius_km"
        private const val KEY_NEARBY_BAND = "nearby_band"
        private const val KEY_NEARBY_RAT = "nearby_rat"
        private const val KEY_STATION_EXCLUDED_BANDS = "station_excluded_lte_bands"
        private const val KEY_STATION_EXCLUDED_CHANNELS = "station_excluded_lte_earfcns"
        private const val DEFAULT_SECTOR_ANGLE_DEG = 120f
        private const val DEFAULT_SECTOR_RADIUS_METERS = 200.0
        private const val MIN_MARKER_SAMPLE_INTERVAL_SEC = 1L
        private const val MAX_MARKER_SAMPLE_INTERVAL_SEC = 60L
        private const val DEFAULT_MARKER_SAMPLE_INTERVAL_SEC = 3L

        const val EXTRA_PREVIEW_LAT = "preview_lat"
        const val EXTRA_PREVIEW_LON = "preview_lon"
        const val EXTRA_PREVIEW_TITLE = "preview_title"
        const val EXTRA_PREVIEW_MODE = "preview_mode"
        const val EXTRA_PICK_MODE = "pick_mode"
        const val EXTRA_PICK_TITLE = "pick_title"
        const val EXTRA_PICK_INIT_WGS_LAT = "pick_init_wgs_lat"
        const val EXTRA_PICK_INIT_WGS_LON = "pick_init_wgs_lon"
        const val EXTRA_PICK_RESULT_WGS_LAT = "pick_result_wgs_lat"
        const val EXTRA_PICK_RESULT_WGS_LON = "pick_result_wgs_lon"
        const val EXTRA_PICK_RESULT_GCJ_LAT = "pick_result_gcj_lat"
        const val EXTRA_PICK_RESULT_GCJ_LON = "pick_result_gcj_lon"
        const val EXTRA_PICK_RESULT_POI_NAME = "pick_result_poi_name"
        const val EXTRA_PICK_RESULT_ADDRESS = "pick_result_address"

        const val MODE_WGS84_RAW = "wgs84_raw"
        const val MODE_GCJ02_CONVERTED = "gcj02_converted"

        fun createIntentForPick(
            context: Context,
            initialWgsLat: Double? = null,
            initialWgsLon: Double? = null,
            title: String = ""
        ): Intent {
            return Intent(context, AmapMapActivity::class.java).apply {
                putExtra(EXTRA_PICK_MODE, true)
                putExtra(EXTRA_PICK_TITLE, title)
                if (initialWgsLat != null && initialWgsLon != null) {
                    putExtra(EXTRA_PICK_INIT_WGS_LAT, initialWgsLat)
                    putExtra(EXTRA_PICK_INIT_WGS_LON, initialWgsLon)
                }
            }
        }

        fun createIntentForWgs84(
            context: Context,
            lat: Double,
            lon: Double,
            title: String
        ): Intent {
            return Intent(context, AmapMapActivity::class.java).apply {
                putExtra(EXTRA_PREVIEW_LAT, lat)
                putExtra(EXTRA_PREVIEW_LON, lon)
                putExtra(EXTRA_PREVIEW_TITLE, title)
                putExtra(EXTRA_PREVIEW_MODE, MODE_WGS84_RAW)
            }
        }

        fun createIntentForGcj02(
            context: Context,
            lat: Double,
            lon: Double,
            title: String
        ): Intent {
            val (gcjLat, gcjLon) = Gcj02.wgs84ToGcj02(lat, lon)
            return Intent(context, AmapMapActivity::class.java).apply {
                putExtra(EXTRA_PREVIEW_LAT, gcjLat)
                putExtra(EXTRA_PREVIEW_LON, gcjLon)
                putExtra(EXTRA_PREVIEW_TITLE, title)
                putExtra(EXTRA_PREVIEW_MODE, MODE_GCJ02_CONVERTED)
            }
        }
    }
}

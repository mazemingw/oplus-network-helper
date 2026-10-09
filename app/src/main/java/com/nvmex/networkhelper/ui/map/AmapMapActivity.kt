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
import com.nvmex.networkhelper.util.map.DrivePointEntity
import com.nvmex.networkhelper.util.map.DriveSampler
import com.nvmex.networkhelper.util.map.DriveHistory
import com.nvmex.networkhelper.util.map.DriveKeepAliveService
import com.nvmex.networkhelper.util.map.Gcj02
import com.nvmex.networkhelper.util.map.SnapshotJson
import com.nvmex.networkhelper.util.network.NetworkPanelRepositoryMultiSim
import com.nvmex.networkhelper.util.network.TelephonySnapshotterMultiSim
import com.nvmex.networkhelper.util.map.driveSnapshotsBySlot
import com.nvmex.networkhelper.xposed.logger.Logger
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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
    private var nearbyJob: Job? = null
    private var lastNearbyCenter: LatLng? = null
    private var lastNearbyAt = 0L
    private var nearbyEnabled = false
    private var nearbyRadiusKm = 3
    private var nearbyBand: String? = null
    private var nearbyRat: String? = null
    private var nearbyResult: NearbyCellsResponse? = null
    private var nearbyRangeCircle: com.amap.api.maps.model.Circle? = null

    private var cellWatchJob: kotlinx.coroutines.Job? = null
    private var mapResumed = false
    private var lastLteKey: String? = null
    private var lastOverlayRat: String? = null
    private var lastNrResultSig: String? = null
    private var lastNrResultAt: Long = 0L

    // ===== DB =====
    private lateinit var dao: DrivePointDao
    private var currentSessionId: String? = null
    private var persistSessionId: String? = null
    @Volatile
    private var hasNewPointsInSession: Boolean = false

    // ===== driving =====
    private var isDriving = false
    private var finishingDrive = false
    private var restoredHistory: DriveHistory? = null
    private var restoreJob: Job? = null
    private var lastToggleAt = 0L
    private var drivePermissionPending = false

    // ===== 视角跟随状态机 =====
    private var followMyLocation = false
    private var lastCenterClickAt = 0L
    private var ignoreCameraMoveCancelOnce = false

    // ===== 3D倾斜 =====
    private var is3DMode = false
    private lateinit var fab2d3d: FloatingActionButton
    private var lastBearingDeg: Float? = null
    private val followTilt3D = 55f

    // ===== UI =====
    private val density by lazy { resources.displayMetrics.density }
    private lateinit var fabCenter: FloatingActionButton
    private var fabDrive: FloatingActionButton? = null
    private lateinit var fabClear: FloatingActionButton
    private lateinit var fabMapType: FloatingActionButton
    private lateinit var fabSectorConfig: FloatingActionButton
    private lateinit var fabSessionRestore: FloatingActionButton
    private lateinit var fabMore: FloatingActionButton
    private lateinit var signalPanel: LinearLayout
    private lateinit var signalPanelTitle: TextView
    private lateinit var nearbyStatus: TextView
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
        applyMapTypeForCurrentMode()

        aMap.setOnMapTouchListener {
            if (followMyLocation) {
                followMyLocation = false
                updateCenterFabUi()
            }
        }

        aMap.setOnCameraChangeListener(object : AMap.OnCameraChangeListener {
            override fun onCameraChange(position: com.amap.api.maps.model.CameraPosition?) {}
            override fun onCameraChangeFinish(position: com.amap.api.maps.model.CameraPosition?) {
                if (ignoreCameraMoveCancelOnce) {
                    ignoreCameraMoveCancelOnce = false
                    return
                }
            }
        })

        if (isPickMode) {
            setupPickMode(root)
            seedPickPointIfNeeded()
        } else {
            dao = AppDb.get(applicationContext).drivePointDao()
            multiSnapshotter = TelephonySnapshotterMultiSim(this)
            panelRepository = NetworkPanelRepositoryMultiSim(this, multiSnapshotter)
            sectorDrawConfig = loadSectorDrawConfig()
            markerSampleIntervalSec = loadMarkerSampleIntervalSec()
            nearbyEnabled = overlayPrefs.getBoolean(KEY_NEARBY_ENABLED, false)
            nearbyRadiusKm = overlayPrefs.getInt(KEY_NEARBY_RADIUS_KM, 3).coerceIn(1, 10)
            nearbyBand = overlayPrefs.getString(KEY_NEARBY_BAND, null)?.takeIf { it.isNotBlank() }
            nearbyRat = overlayPrefs.getString(KEY_NEARBY_RAT, null)?.takeIf { it == "LTE" || it == "NR" }

            sampler = DriveSampler(
                aMap = aMap,
                scope = scope,
                getLatLng = { lastLatLng },
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
                    dao.insertBatch(points.map { p ->
                        DrivePointEntity(
                            sessionId = sid,
                            ts = p.ts,
                            simSlot = p.simSlot,
                            lat = p.latLng.latitude,
                            lng = p.latLng.longitude,
                            rsrpDbm = p.rsrpDbm,
                            isHandover = p.isHandover,
                            cellKey = p.cellKey ?: "",
                            snapshotJson = SnapshotJson.toJson(p.snapshot)
                        )
                    })
                    hasNewPointsInSession = true
                },
                persistEnabled = { isDriving && !persistSessionId.isNullOrBlank() },
                onHandover = { s ->
                    // Sampling is dual-SIM, but overlays follow the selected TAB.
                    if (latestSnapshots[selectedSimSlot]?.subId != s.subId) return@DriveSampler
                    s.toNrQueryArgsOrNull()?.let {
                        queryNrOverlay(it, lteFallback = s.toLteQueryArgsOrNull())
                        return@DriveSampler
                    }
                    s.toLteQueryArgsOrNull()?.let { queryLteOverlay(it) }
                }
            )

            sampler.bindClickToShowInfo()

            lteOverlay = LteCellOverlayManager(
                aMap = aMap,
                api = apiService,
                localRepository = localRepository,
                scope = scope,
                cacheDao = AppDb.get(applicationContext).cellOverlayCacheDao(),
                cacheTtlMs = 7L * 24 * 3600_000L,
                serverCoord = ServerCoord.WGS84
            )
            nrOverlay = NrCellOverlayManager(
                aMap = aMap,
                api = apiService,
                localRepository = localRepository,
                scope = scope,
                serverCoord = ServerCoord.WGS84
            )
            nearbyLteOverlay = LteCellOverlayManager(
                aMap = aMap, api = apiService, localRepository = localRepository, scope = scope,
                cacheDao = AppDb.get(applicationContext).cellOverlayCacheDao(),
                serverCoord = ServerCoord.WGS84, maxHistoryMarkers = 1000
            )
            nearbyNrOverlay = NrCellOverlayManager(
                aMap = aMap, api = apiService, localRepository = localRepository, scope = scope,
                serverCoord = ServerCoord.WGS84, maxHistoryMarkers = 1000
            )
            applySectorDrawConfigToOverlays()
            lteOverlay.bindMapLoadedListener()

            triggerTriangleOnceOnEnter()
            setupFabs(root)
            setupSignalPanel(root)
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
            ContextCompat.getColorStateList(this, android.R.color.holo_red_dark)
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

        android.widget.Toast.makeText(
            this,
            getString(R.string.map_pick_hint),
            android.widget.Toast.LENGTH_SHORT
        ).show()
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
            android.widget.Toast.makeText(
                this,
                getString(R.string.map_pick_no_selection),
                android.widget.Toast.LENGTH_SHORT
            )
                .show()
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

        fabDrive = FloatingActionButton(this).apply {
            setOnClickListener { toggleDrive() }
        }
        root.addView(
            fabDrive,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.END or Gravity.BOTTOM
                rightMargin = dp(18)
                bottomMargin = dp(28 + 56 + 12)
            }
        )
        applyDriveUi(isDriving)

        fabClear = FloatingActionButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_delete)
            setOnClickListener { clearCurrentSession() }
        }
        root.addView(
            fabClear,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.END or Gravity.BOTTOM
                rightMargin = dp(18)
                bottomMargin = dp(28 + (56 + 12) * 3)
            }
        )
        applyFabDefaultUi(fabClear)

        fab2d3d = FloatingActionButton(this).apply {
            setOnClickListener { toggle2D3D() }
        }
        root.addView(
            fab2d3d,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.END or Gravity.BOTTOM
                rightMargin = dp(18)
                bottomMargin = dp(28 + (56 + 12) * 4)
            }
        )
        applyFabDefaultUi(fab2d3d)
        update2D3DFabUi()

        fabMapType = FloatingActionButton(this).apply {
            setOnClickListener { toggleBaseMapMode() }
        }
        root.addView(
            fabMapType,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.END or Gravity.BOTTOM
                rightMargin = dp(18)
                bottomMargin = dp(28 + (56 + 12) * 5)
            }
        )
        applyFabDefaultUi(fabMapType)
        updateMapTypeFabUi()

        fabSectorConfig = FloatingActionButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_manage)
            contentDescription = getString(R.string.map_drawing_probe_settings)
            setOnClickListener { showSectorConfigDialog() }
        }
        root.addView(
            fabSectorConfig,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.END or Gravity.BOTTOM
                rightMargin = dp(18)
                bottomMargin = dp(28 + (56 + 12) * 6)
            }
        )
        applyFabDefaultUi(fabSectorConfig)

        fabSessionRestore = FloatingActionButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_recent_history)
            setOnClickListener { showSessionRestoreDialog() }
        }
        root.addView(
            fabSessionRestore,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.END or Gravity.BOTTOM
                rightMargin = dp(18)
                bottomMargin = dp(28 + (56 + 12) * 7)
            }
        )
        applyFabDefaultUi(fabSessionRestore)

        fabMore = FloatingActionButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_more)
            contentDescription = "更多地图操作"
            setOnClickListener {
                val expanded = fabClear.visibility != View.VISIBLE
                listOf(fabClear, fab2d3d, fabMapType, fabSectorConfig, fabSessionRestore)
                    .forEach { it.visibility = if (expanded) View.VISIBLE else View.GONE }
                setImageResource(if (expanded) android.R.drawable.ic_menu_close_clear_cancel else android.R.drawable.ic_menu_more)
            }
        }
        root.addView(
            fabMore,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.END or Gravity.BOTTOM
                rightMargin = dp(18)
                bottomMargin = dp(28 + (56 + 12) * 2)
            }
        )
        applyFabDefaultUi(fabMore)
        listOf(fabClear, fab2d3d, fabMapType, fabSectorConfig, fabSessionRestore)
            .forEach { it.visibility = View.GONE }
    }

    private fun setupSignalPanel(root: FrameLayout) {
        signalPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xEFFFFFFF.toInt())
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), 0x55333333)
            }
            elevation = dp(6).toFloat()
        }
        signalPanelTitle = TextView(this).apply {
            text = getString(R.string.map_dual_sim_tabs_title)
            setTextColor(Color.DKGRAY)
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        signalPanel.addView(signalPanelTitle, LinearLayout.LayoutParams(-1, dp(24)))

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        (0..1).forEach { slot ->
            val tab = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(6), 0, dp(6), 0)
                setOnClickListener {
                    selectedSimSlot = slot
                    sampler.setVisibleSimSlot(slot)
                    val states = displayedSnapshots()
                    updateSignalPanel(states)
                    lteOverlay.clear()
                    nrOverlay.clear()
                    lastLteKey = null
                    lastOverlayRat = null
                    states[slot]?.let { triggerOverlayForState(it) }
                }
                contentDescription = "SIM${slot + 1} 路测地图"
            }
            signalPanelTabs[slot] = tab
            val label = TextView(this).apply {
                text = "SIM${slot + 1}  -"
                textSize = 12f
                gravity = Gravity.CENTER
            }
            val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                this.progress = 0
            }
            signalPanelValueViews[slot] = label
            signalPanelProgressViews[slot] = progress
            tab.addView(label, LinearLayout.LayoutParams(-1, dp(42)))
            tab.addView(progress, LinearLayout.LayoutParams(-1, dp(7)))
            row.addView(tab, LinearLayout.LayoutParams(0, dp(58), 1f))
        }
        signalPanel.addView(row, LinearLayout.LayoutParams(-1, dp(60)))
        root.addView(
            signalPanel,
            FrameLayout.LayoutParams(dp(290), dp(100)).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                // 仅移动悬浮面板；MapView 仍从屏幕顶端绘制，不增加不透明“额头”。
                topMargin = statusBarHeightPx() + dp(8)
            }
        )
        nearbyStatus = TextView(this).apply {
            setTextColor(Color.DKGRAY)
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(5), dp(8), dp(5))
            setBackgroundColor(0xEFFFFFFF.toInt())
            visibility = View.GONE
        }
        root.addView(nearbyStatus, FrameLayout.LayoutParams(dp(290), -2).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = statusBarHeightPx() + dp(112)
        })
    }

    private fun statusBarHeightPx(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else dp(24)
    }

    private fun updateSignalPanel(states: Map<Int, NetworkPanelUiState>) {
        if (!::signalPanel.isInitialized) return
        signalPanelTitle.setText(if (restoredHistory == null) R.string.map_dual_sim_tabs_title
            else R.string.map_history_tabs_title)
        (0..1).forEach { slot ->
            val state = states[slot]
            val rsrp = state?.let {
                val raw = if (it.cellType.equals("NR", true) && it.ssRsrp != "-") it.ssRsrp else it.rsrp
                parseSignalDbm(raw)
            }
            signalPanelValueViews[slot]?.apply {
                val rat = state?.cellType?.takeIf { it != "-" } ?: "-"
                val historyPoints = restoredHistory?.pointsBySlot?.get(slot)
                text = if (restoredHistory != null && historyPoints.isNullOrEmpty()) {
                    getString(R.string.map_history_sim_empty, slot + 1)
                } else {
                    "SIM${slot + 1} · $rat\nRSRP ${rsrp?.let { "$it dBm" } ?: "-"}"
                }
                setTextColor(if (selectedSimSlot == slot) fabBlue else Color.DKGRAY)
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
                    if (selectedSimSlot == slot) fabBlue else 0xFF90A4AE.toInt()
                )
            }
        }
    }

    private fun parseSignalDbm(raw: String): Int? =
        Regex("""-?\d+""").find(raw.trim())?.value?.toIntOrNull()

    // Header and both point streams use exactly the same subscription callbacks.
    // Never poll allCellInfo directly or invent a SIM1 snapshot on failure.
    private fun snapshotAllSims(): Map<Int, NetworkPanelUiState> = latestSnapshots

    private fun displayedSnapshots(): Map<Int, NetworkPanelUiState> =
        restoredHistory?.snapshots ?: latestSnapshots

    private fun updateCenterFabUi() {
        if (followMyLocation) {
            fabCenter.backgroundTintList =
                ContextCompat.getColorStateList(this, android.R.color.holo_green_dark)
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
                withContext(Dispatchers.IO) { dao.deleteSession(sid) }
                hasNewPointsInSession = false
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
            requestDriveStart()
        } else {
            isDriving = false
            sampler.stop()
            DriveKeepAliveService.stop(this)
            if (!mapResumed) locClient?.stopLocation()
            applyDriveUi(false)

            finishingDrive = true
            scope.launch {
                val error = sampler.awaitPersistence()
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
                } else showSaveDialog(sessionId = sid)
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
            updateSignalPanel(latestSnapshots)
            val sid = newSessionId()
            currentSessionId = sid
            persistSessionId = sid
            hasNewPointsInSession = false
            isDriving = true
            locClient?.startLocation()
            sampler.start()
            applyDriveUi(true)
            val notifications = NotificationManagerCompat.from(this)
            if (!notifications.areNotificationsEnabled() ||
                notifications.getNotificationChannel(DriveKeepAliveService.CHANNEL_ID)?.importance ==
                android.app.NotificationManager.IMPORTANCE_NONE
            ) showDriveMessage(R.string.map_drive_notification_disabled)
        } catch (error: Exception) {
            isDriving = false
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
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
    }

    private fun showSaveDialog(sessionId: String) {
        newDialogBuilder()
            .setTitle("保存本次路测会话？")
            .setMessage("保存：保留本次采样点\n不保存：删除本次采样点")
            .setCancelable(false)
            .setPositiveButton("保存") { _, _ ->
                currentSessionId = null
                persistSessionId = null
                hasNewPointsInSession = false
            }
            .setNegativeButton("不保存") { _, _ ->
                scope.launch(Dispatchers.IO) { runCatching { dao.deleteSession(sessionId) } }
                currentSessionId = null
                persistSessionId = null
                hasNewPointsInSession = false
            }
            .show()
    }

    private fun applyDriveUi(isDriving: Boolean) {
        val btn = fabDrive ?: return
        applyFabIconUi(btn)

        if (isDriving) {
            btn.setImageResource(android.R.drawable.ic_media_pause)
            btn.backgroundTintList =
                ContextCompat.getColorStateList(this, android.R.color.holo_red_dark)
        } else {
            btn.setImageResource(android.R.drawable.ic_media_play)
            btn.backgroundTintList =
                android.content.res.ColorStateList.valueOf(fabBlue)
        }
    }

    private fun initMyLocation() {
        aMap.setLocationSource(this)
        aMap.myLocationStyle = MyLocationStyle()
            .myLocationType(MyLocationStyle.LOCATION_TYPE_LOCATION_ROTATE_NO_CENTER)
            .interval(1000L)
        aMap.isMyLocationEnabled = true
    }

    override fun activate(listener: LocationSource.OnLocationChangedListener?) {
        amapListener = listener

        if (locClient == null) {
            locClient = AMapLocationClient(applicationContext).apply {
                val opt = AMapLocationClientOption().apply {
                    locationMode = AMapLocationClientOption.AMapLocationMode.Hight_Accuracy
                    isOnceLocation = false
                    interval = 1000L
                    isNeedAddress = false
                    isWifiScan = true
                    isMockEnable = true
                }
                setLocationOption(opt)

                setLocationListener { loc ->
                    if (loc != null && loc.errorCode == 0) {
                        val p = LatLng(loc.latitude, loc.longitude)
                        lastLatLng = p
                        if (mapResumed) amapListener?.onLocationChanged(loc)
                        lastBearingDeg = runCatching { loc.bearing }.getOrNull()

                        // Continue collecting fixes without moving a paused map camera.
                        if (!mapResumed) return@setLocationListener
                        if (isFirstFix) {
                            isFirstFix = false

                            if (shouldSkipFirstFixCenter) {
                                shouldSkipFirstFixCenter = false
                            } else {
                                ignoreCameraMoveCancelOnce = true
                                val initZoom = 17f
                                val initTilt = if (is3DMode) followTilt3D else 0f
                                val initBearing = if (is3DMode) (lastBearingDeg ?: 0f) else 0f
                                val cam = com.amap.api.maps.model.CameraPosition(p, initZoom, initTilt, initBearing)
                                aMap.moveCamera(CameraUpdateFactory.newCameraPosition(cam))
                            }
                        } else if (followMyLocation) {
                            applyFollowCamera(p, lastBearingDeg)
                        }
                        if (!isPickMode) probeNearbyCells()
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
        }
    }

    override fun onPause() {
        super.onPause()
        mapResumed = false
        mapView.onPause()
        // Keep per-SIM telemetry fresh while a running road test is backgrounded.
        if (isDriving) locClient?.startLocation()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!isPickMode) DriveKeepAliveService.stop(this)

        if (isDriving) {
            val sid = currentSessionId
            isDriving = false
            runCatching { sampler.stop() }
            if (sid != null) {
                // The Activity scope is about to be cancelled. Finish deletion after its writers stop.
                CoroutineScope(Dispatchers.IO).launch {
                    sampler.awaitPersistence()
                    runCatching { dao.deleteSession(sid) }
                }
            }
        }

        scope.cancel()
        mapView.onDestroy()
        locClient?.onDestroy()
        locClient = null
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView.onSaveInstanceState(outState)
    }

    private fun showSessionRestoreDialog() {
        if (isDriving || finishingDrive) {
            showDriveMessage(R.string.map_history_stop_recording)
            return
        }
        scope.launch {
            val sessions = withContext(Dispatchers.IO) {
                runCatching { dao.getSessionSummaries(MAX_SESSION_LIST) }.getOrDefault(emptyList())
            }
            if (sessions.isEmpty()) {
                android.widget.Toast.makeText(
                    this@AmapMapActivity,
                    "暂无可恢复的 Session",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                return@launch
            }

            val labels = sessions.map { summary ->
                val start = formatTs(summary.startTs)
                val end = formatTs(summary.endTs)
                "${summary.sessionId}  (${summary.pointCount}点)\n$start ~ $end"
            }.toTypedArray()

            var selected = 0
            val dialog = newDialogBuilder()
                .setTitle("选择要恢复的 Session")
                .setSingleChoiceItems(labels, selected) { _, which ->
                    selected = which
                }
                .setPositiveButton("恢复") { _, _ ->
                    val target = sessions.getOrNull(selected) ?: return@setPositiveButton
                    restoreSessionPoints(target.sessionId)
                }
                .setNeutralButton("删除", null)
                .setNegativeButton("取消", null)
                .show()

            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
                val target = sessions.getOrNull(selected) ?: return@setOnClickListener
                showDeleteSessionConfirm(target.sessionId) {
                    dialog.dismiss()
                    showSessionRestoreDialog()
                }
            }
        }
    }

    private fun restoreSessionPoints(sessionId: String) {
        if (isDriving || finishingDrive) return
        restoreJob?.cancel()
        restoreJob = scope.launch {
            val rows = withContext(Dispatchers.IO) {
                runCatching { dao.getBySession(sessionId) }.getOrDefault(emptyList())
            }
            if (rows.isEmpty()) {
                android.widget.Toast.makeText(
                    this@AmapMapActivity,
                    "Session 无数据：$sessionId",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                return@launch
            }

            val points = rows.mapNotNull { it.toDrivePointOrNull() }
            if (points.isEmpty()) {
                android.widget.Toast.makeText(
                    this@AmapMapActivity,
                    "Session 数据解析失败：$sessionId",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                return@launch
            }

            logRestoreFieldStats(sessionId = sessionId, points = points)
            if (isDriving || finishingDrive) return@launch
            val history = DriveHistory(points)
            restoredHistory = history
            selectedSimSlot = history.initialSlot(selectedSimSlot)
            sampler.renderHistory(points, clearExisting = true)
            sampler.setVisibleSimSlot(selectedSimSlot)
            updateSignalPanel(history.snapshots)
            followMyLocation = false
            shouldSkipFirstFixCenter = true
            updateCenterFabUi()
            history.firstPoint?.let { first ->
                ignoreCameraMoveCancelOnce = true
                aMap.moveCamera(CameraUpdateFactory.newLatLngZoom(first.latLng, 17f))
            }
            lteOverlay.clear()
            nrOverlay.clear()
            lastLteKey = null
            lastOverlayRat = null
            history.snapshots[selectedSimSlot]?.let { triggerOverlayForState(it) }
            Logger.log("Drive restore session=$sessionId loaded=${points.size}")
            android.widget.Toast.makeText(
                this@AmapMapActivity,
                "已恢复 $sessionId（${points.size}点）",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun showDeleteSessionConfirm(sessionId: String, onDeleted: () -> Unit) {
        if (isDriving && currentSessionId == sessionId) {
            android.widget.Toast.makeText(
                this,
                "当前 Session 正在采样，请先停止",
                android.widget.Toast.LENGTH_SHORT
            ).show()
            return
        }

        newDialogBuilder()
            .setTitle("删除 Session")
            .setMessage("确认删除 $sessionId 的全部打点记录吗？")
            .setPositiveButton("删除") { _, _ ->
                scope.launch {
                    withContext(Dispatchers.IO) {
                        runCatching { dao.deleteSession(sessionId) }
                    }
                    sampler.clear()
                    restoredHistory = null
                    updateSignalPanel(latestSnapshots)
                    android.widget.Toast.makeText(
                        this@AmapMapActivity,
                        "已删除 $sessionId",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
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
                    if (mapResumed) states[selectedSimSlot]?.let { triggerOverlayForState(it) }
                }
            }
        }
    }

    private fun triggerOverlayForState(state: NetworkPanelUiState): Boolean {
        val nrArgs = state.toNrQueryArgsOrNull()
        val lteFallback = state.toLteQueryArgsOrNull()
        if (nrArgs != null) {
            if (lastOverlayRat != "NR") {
                lastOverlayRat = "NR"
            }
            queryNrOverlay(nrArgs, lteFallback = lteFallback)
            return true
        }

        val lteArgs = lteFallback ?: return false
        if (lastOverlayRat != "LTE") {
            lastOverlayRat = "LTE"
            lastLteKey = null
        }
        return queryLteOverlayIfNeeded(lteArgs)
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
            android.content.res.ColorStateList.valueOf(fabBlue)
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
        fab.compatElevation = dp(6).toFloat()
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
            nearbyResult?.let {
                nearbyLteOverlay.renderNearbyCells(it.lte)
                nearbyNrOverlay.renderNearbyCells(it.nr)
            }
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
                val bandInput = bandEdit.text?.toString()?.trim().orEmpty()
                val rat = when (ratSelector.selectedItemPosition) { 1 -> "LTE"; 2 -> "NR"; else -> null }
                val bandMatch = Regex("^([bBnN])?([1-9]\\d{0,2})$").matchEntire(bandInput)
                val prefix = bandMatch?.groupValues?.get(1).orEmpty()
                val bandRat = when (prefix.lowercase(Locale.ROOT)) { "b" -> "LTE"; "n" -> "NR"; else -> null }
                bandLayout.error = when {
                    bandInput.isNotEmpty() && bandMatch == null -> getString(R.string.map_nearby_band_invalid)
                    rat != null && bandRat != null && rat != bandRat -> getString(R.string.map_nearby_band_rat_mismatch)
                    else -> null
                }
                angleLayout.error = if (angle == null || !angle.isFinite() || angle !in 20f..180f)
                    getString(R.string.map_parameter_invalid) else null
                distanceLayout.error = if (radius == null || !radius.isFinite() || radius !in 50.0..2000.0)
                    getString(R.string.map_parameter_invalid) else null
                sampleIntervalLayout.error = if (sampleIntervalSecInput == null ||
                    sampleIntervalSecInput !in MIN_MARKER_SAMPLE_INTERVAL_SEC..MAX_MARKER_SAMPLE_INTERVAL_SEC)
                    getString(R.string.map_parameter_invalid) else null
                if (angleLayout.error != null || distanceLayout.error != null || sampleIntervalLayout.error != null ||
                    bandLayout.error != null)
                    return false

                sectorDrawConfig = normalizeSectorDrawConfig(angle!!, radius!!)
                markerSampleIntervalSec = normalizeMarkerSampleIntervalSec(sampleIntervalSecInput!!)
                saveSectorDrawConfig(sectorDrawConfig)
                saveMarkerSampleIntervalSec(markerSampleIntervalSec)
                val band = bandMatch?.let {
                    (when (bandRat) { "LTE" -> "B"; "NR" -> "n"; else -> "" }) + it.groupValues[2]
                }
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

                lteOverlay.clear()
                nrOverlay.clear()
                lastLteKey = null
                lastOverlayRat = null
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

    private fun clearNearbyCells() {
        nearbyJob?.cancel()
        nearbyJob = null
        lastNearbyAt = 0L
        lastNearbyCenter = null
        nearbyResult = null
        nearbyLteOverlay.clear()
        nearbyNrOverlay.clear()
        nearbyRangeCircle?.remove()
        nearbyRangeCircle = null
        nearbyStatus.visibility = View.GONE
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
                val response = apiService.queryNearbyCells(NearbyCellsRequest(
                    latitude, longitude, radius, band = band, rat = rat))
                val result = response.body()
                if (!response.isSuccessful || result?.ok != true) {
                    val error = response.errorBody()?.string()?.let {
                        runCatching { org.json.JSONObject(it).optString("error") }.getOrNull()
                    }
                    nearbyStatus.text = when (error) {
                        "invalid_band" -> getString(R.string.map_nearby_band_unsupported, band.orEmpty())
                        "band_rat_mismatch" -> getString(R.string.map_nearby_band_rat_mismatch)
                        else -> getString(R.string.map_nearby_failed)
                    }
                    return@launch
                }
                nearbyResult = result
                lastNearbyCenter = center
                val lteCount = nearbyLteOverlay.renderNearbyCells(result.lte)
                val nrCount = nearbyNrOverlay.renderNearbyCells(result.nr)
                nearbyRangeCircle?.remove()
                nearbyRangeCircle = aMap.addCircle(com.amap.api.maps.model.CircleOptions()
                    .center(center).radius(radius * 1000.0)
                    .strokeColor(0x88448AFF.toInt()).strokeWidth(2f).fillColor(0x08448AFF))
                nearbyStatus.text = getString(R.string.map_nearby_result, radius, lteCount, nrCount) +
                    (rat?.let { if (it == "LTE") " · 4G" else " · 5G" } ?: "") +
                    (band?.let { " · $it" } ?: "") +
                    if (result.truncated) getString(R.string.map_nearby_truncated) else ""
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

    private fun String.toIntOrNullDash(): Int? {
        val t = trim()
        if (t.isEmpty() || t == "-") return null
        return t.toIntOrNull()
    }

    private fun String.toLongOrNullDash(): Long? {
        val t = trim()
        if (t.isEmpty() || t == "-") return null
        return t.toLongOrNull()
    }

    private fun DrivePointEntity.toDrivePointOrNull(): DrivePoint? {
        val snap = runCatching { SnapshotJson.fromJson(snapshotJson) }
            .getOrElse { return null }
        return DrivePoint(
            latLng = LatLng(lat, lng),
            ts = ts,
            simSlot = simSlot,
            rsrpDbm = rsrpDbm,
            isHandover = isHandover,
            cellKey = cellKey,
            snapshot = snap
        )
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

    private fun NetworkPanelUiState.pickLteForQuery(): NetworkPanelUiState? {
        return when {
            cellType.equals("LTE", true) -> this
            nsaLteAnchor != null -> nsaLteAnchor
            else -> null
        }
    }

    private data class LteQueryArgs(
        val tac: Int,
        val earfcn: Int,
        val eci: Long?,
        val pci: Int?
    )

    private data class NrQueryArgs(
        val gcellId: String?,
        val nrTac: Int?,
        val nrArfcn: Int?,
        val nrPci: Int?
    )

    private fun NetworkPanelUiState.toLteQueryArgsOrNull(): LteQueryArgs? {
        val lte = pickLteForQuery() ?: return null
        val tac = lte.tac.toIntOrNullDash() ?: return null
        val earfcn = lte.arfcn.toIntOrNullDash() ?: return null
        val pci = lte.pci.toIntOrNullDash()
        val eci = lte.ci.toLongOrNullDash()
        return LteQueryArgs(tac = tac, earfcn = earfcn, eci = eci, pci = pci)
    }

    private fun NetworkPanelUiState.toNrQueryArgsOrNull(): NrQueryArgs? {
        if (!cellType.equals("NR", true)) return null
        val gcellId = ci.trim().takeIf { it.isNotBlank() && it != "-" }
        val nrTac = tac.toIntOrNullDash()
        val nrArfcn = arfcn.toIntOrNullDash()
        val nrPci = pci.toIntOrNullDash()
        if (gcellId == null && nrTac == null && nrArfcn == null && nrPci == null) return null
        return NrQueryArgs(
            gcellId = gcellId,
            nrTac = nrTac,
            nrArfcn = nrArfcn,
            nrPci = nrPci
        )
    }

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

    private var lastQueryToastAt = 0L

    private fun toastQueryTriggered(args: LteQueryArgs) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastQueryToastAt < 1200L) return

        lastQueryToastAt = now

        val msg = buildString {
            append("LTE query ")
            append("tac=").append(args.tac)
            append(" earfcn=").append(args.earfcn)
            args.eci?.let { append(" eci=").append(it) }
            args.pci?.let { append(" pci=").append(it) }
        }
        Logger.log("LTE query tac=${args.tac} earfcn=${args.earfcn} eci=${args.eci} pci=${args.pci}")
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun toastNrResult(sig: String, message: String) {
        val now = SystemClock.elapsedRealtime()
        val minIntervalMs = 2500L
        if (sig == lastNrResultSig && now - lastNrResultAt < minIntervalMs) return
        lastNrResultSig = sig
        lastNrResultAt = now
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun queryLteOverlay(args: LteQueryArgs) {
        toastQueryTriggered(args)
        lteOverlay.queryAndShow(
            tac = args.tac,
            earfcn = args.earfcn,
            eci = args.eci,
            pci = args.pci,
            cellId = null
        )
    }

    private fun queryLteOverlayIfNeeded(args: LteQueryArgs): Boolean {
        val key = "t=${args.tac}|eci=${args.eci ?: "x"}|e=${args.earfcn}|pci=${args.pci ?: "x"}"
        if (key == lastLteKey) return false
        lastLteKey = key
        queryLteOverlay(args)
        return true
    }

    private fun queryNrOverlay(args: NrQueryArgs, lteFallback: LteQueryArgs? = null) {
        val dispatched = nrOverlay.queryAndShow(
            gcellId = args.gcellId,
            nrTac = args.nrTac,
            nrArfcn = args.nrArfcn,
            nrPci = args.nrPci,
            onResult = { result ->
                when (result) {
                    is NrCellOverlayManager.QueryResult.Rendered -> {
                        val coordText = if (result.firstLat != null && result.firstLon != null) {
                            " (${String.format("%.5f", result.firstLat)}, ${String.format("%.5f", result.firstLon)})"
                        } else ""
                        toastNrResult(
                            sig = "ok:${result.stationCount}:${result.rowCount}",
                            message = "NR结果: 站点=${result.stationCount}, 小区=${result.rowCount}$coordText"
                        )
                    }

                    is NrCellOverlayManager.QueryResult.NoData -> {
                        toastNrResult(
                            sig = "nodata:${result.reason}",
                            message = "NR结果: 无可绘制数据(${result.reason})"
                        )
                        lteFallback?.let { queryLteOverlayIfNeeded(it) }
                    }

                    is NrCellOverlayManager.QueryResult.Failed -> {
                        toastNrResult(
                            sig = "fail:${result.reason}",
                            message = "NR查询失败(${result.reason})"
                        )
                        lteFallback?.let { queryLteOverlayIfNeeded(it) }
                    }
                }
            }
        )
        if (dispatched) {
            Logger.log(
                "NR request sent gcellId=${args.gcellId} tac=${args.nrTac} nrarfcn=${args.nrArfcn} pci=${args.nrPci}"
            )
        }
    }

    private fun toggle2D3D() {
        is3DMode = !is3DMode
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
        aMap.animateCamera(CameraUpdateFactory.newCameraPosition(cam))

        if (followMyLocation) {
            lastLatLng?.let { ll -> applyFollowCamera(ll, lastBearingDeg) }
        }
    }

    private fun update2D3DFabUi() {
        if (!::fab2d3d.isInitialized) return
        if (is3DMode) {
            fab2d3d.setImageResource(android.R.drawable.ic_menu_compass)
        } else {
            fab2d3d.setImageResource(android.R.drawable.ic_dialog_map)
        }
        applyFabIconUi(fab2d3d)
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
        if (aMap.mapType != targetMapType) {
            aMap.mapType = targetMapType
        }
    }

    private fun updateMapTypeFabUi() {
        if (!::fabMapType.isInitialized) return
        if (baseMapMode == BaseMapMode.SATELLITE) {
            fabMapType.setImageResource(android.R.drawable.ic_menu_gallery)
            fabMapType.contentDescription = "地图模式：卫星"
        } else {
            fabMapType.setImageResource(android.R.drawable.ic_menu_mapmode)
            fabMapType.contentDescription = if (isSystemNightMode()) {
                "地图模式：标准(夜间)"
            } else {
                "地图模式：标准(日间)"
            }
        }
        applyFabIconUi(fabMapType)
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

    private data class SectorDrawConfig(
        val angleDeg: Float,
        val radiusMeters: Double
    )

    private enum class BaseMapMode(val value: String) {
        STANDARD("standard"),
        SATELLITE("satellite")
    }

    companion object {
        private const val MAX_RESTORE_POINTS = 3000
        private const val MAX_SESSION_LIST = 80
        private const val PREF_MAP_OVERLAY = "map_overlay_prefs"
        private const val KEY_SECTOR_ANGLE = "sector_angle_deg"
        private const val KEY_SECTOR_RADIUS = "sector_radius_meters"
        private const val KEY_MARKER_SAMPLE_INTERVAL_SEC = "marker_sample_interval_sec"
        private const val KEY_BASE_MAP_MODE = "base_map_mode"
        private const val KEY_NEARBY_ENABLED = "nearby_enabled"
        private const val KEY_NEARBY_RADIUS_KM = "nearby_radius_km"
        private const val KEY_NEARBY_BAND = "nearby_band"
        private const val KEY_NEARBY_RAT = "nearby_rat"
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

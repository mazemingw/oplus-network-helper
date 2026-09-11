package com.nvmex.networkhelper.viewmodel.signal

import android.annotation.SuppressLint
import android.app.Application
import android.location.LocationManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.amap.api.location.AMapLocation
import com.amap.api.location.AMapLocationClient
import com.amap.api.location.AMapLocationClientOption
import com.nvmex.networkhelper.R
import com.nvmex.networkhelper.model.network.NetworkPanelUiState
import com.nvmex.networkhelper.network.api.ApiService
import com.nvmex.networkhelper.network.base.ApiResult
import com.nvmex.networkhelper.network.base.safeApiCall
import com.nvmex.networkhelper.network.model.BatchUpsertResp
import com.nvmex.networkhelper.network.model.LteBatchUpsertReq
import com.nvmex.networkhelper.network.model.LteCellParamUploadItem
import com.nvmex.networkhelper.network.model.NrBatchUpsertReq
import com.nvmex.networkhelper.network.model.NrCellParamUploadItem
import com.nvmex.networkhelper.ui.network.FollowRecordUiState
import com.nvmex.networkhelper.ui.network.FollowSiteType
import com.nvmex.networkhelper.ui.network.FollowVendor
import com.nvmex.networkhelper.util.map.Gcj02
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import kotlin.coroutines.resume

data class UploadResult(
    val isSuccess: Boolean,
    val error: String? = null,
    val message: String? = null
)

@HiltViewModel
class FollowRecordViewModel @Inject constructor(
    application: Application,
    private val apiService: ApiService
) : AndroidViewModel(application) {

    companion object {
        private const val DEFAULT_SOURCE = "manual_follow_record"
        private val SUFFIX_REGEX = Regex("""-[ZHA][5L][HW]$""")
    }

    private val appContext = application.applicationContext

    private val _uiState = MutableStateFlow(FollowRecordUiState())
    val uiState: StateFlow<FollowRecordUiState> = _uiState.asStateFlow()

    fun resetState() {
        _uiState.value = FollowRecordUiState()
    }

    fun setCellName(value: String) {
        _uiState.update {
            it.copy(
                cellName = value,
                submitSuccess = false,
                submitMessage = null,
                errorMessage = null
            )
        }
    }

    fun setVendor(vendor: FollowVendor) {
        _uiState.update {
            it.copy(
                selectedVendor = if (it.selectedVendor == vendor) null else vendor,
                submitSuccess = false,
                submitMessage = null,
                errorMessage = null
            )
        }
    }

    fun setSiteType(siteType: FollowSiteType) {
        _uiState.update {
            it.copy(
                selectedSiteType = if (it.selectedSiteType == siteType) null else siteType,
                submitSuccess = false,
                submitMessage = null,
                errorMessage = null
            )
        }
    }

    fun applyQuickSuffix(panelState: NetworkPanelUiState) {
        val s = _uiState.value
        val vendor = s.selectedVendor
        val siteType = s.selectedSiteType
        val techCode = techCodeOf(panelState)

        if (vendor == null || siteType == null || techCode == null) {
            _uiState.update {
                it.copy(
                    errorMessage = when {
                        vendor == null -> appContext.getString(R.string.follow_record_select_vendor_first)
                        siteType == null -> appContext.getString(R.string.follow_record_select_site_type_first)
                        else -> appContext.getString(R.string.follow_record_suffix_support_lte_nr)
                    },
                    submitMessage = null,
                    submitSuccess = false
                )
            }
            return
        }

        val suffix = vendor.code + techCode + siteType.code
        val base = buildBaseCellName(s.cellName, s.poiName)

        _uiState.update {
            it.copy(
                cellName = if (base.isBlank()) suffix else "$base-$suffix",
                errorMessage = null,
                submitMessage = null,
                submitSuccess = false
            )
        }
    }

    fun clearResultMessage() {
        _uiState.update {
            it.copy(
                submitSuccess = false,
                submitMessage = null,
                errorMessage = null
            )
        }
    }

    fun applyPickedLocation(
        wgsLat: Double,
        wgsLon: Double,
        gcjLat: Double? = null,
        gcjLon: Double? = null,
        poiName: String? = null,
        address: String? = null
    ) {
        val pickedPoi = poiName?.takeIf { it.isNotBlank() }
        val pickedAddress = address?.takeIf { it.isNotBlank() }

        _uiState.update { old ->
            val finalPoi = pickedPoi ?: old.poiName
                ?: appContext.getString(R.string.map_pick_default_title)
            val finalAddress = pickedAddress ?: old.address
                ?: appContext.getString(R.string.follow_record_manual_pick_address)

            old.copy(
                isLoadingLocation = false,
                longitude = wgsLon,
                latitude = wgsLat,
                coordType = "WGS84",
                rawLongitude = gcjLon ?: wgsLon,
                rawLatitude = gcjLat ?: wgsLat,
                rawCoordType = if (gcjLat != null && gcjLon != null) {
                    "GCJ02(MAP_PICK)"
                } else {
                    "WGS84(MAP_PICK)"
                },
                poiName = finalPoi,
                address = finalAddress,
                cellName = if (old.cellName.isBlank()) {
                    finalPoi
                } else {
                    old.cellName
                },
                submitSuccess = false,
                submitMessage = null,
                errorMessage = null
            )
        }
    }

    @SuppressLint("MissingPermission")
    fun loadCurrentLocation() {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoadingLocation = true,
                    errorMessage = null,
                    submitMessage = null,
                    submitSuccess = false
                )
            }

            try {
                val locationManager = appContext.getSystemService(LocationManager::class.java)
                val gpsEnabled = locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true
                val networkEnabled = locationManager?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true

                if (!gpsEnabled && !networkEnabled) {
                    _uiState.update {
                        it.copy(
                            isLoadingLocation = false,
                            errorMessage = appContext.getString(R.string.follow_record_location_service_disabled)
                        )
                    }
                    return@launch
                }

                val loc = getCurrentLocationByAmap()

                val rawLat = loc.latitude
                val rawLon = loc.longitude
                val rawCoordType = loc.coordType?.trim()?.uppercase()

                val (wgsLat, wgsLon) = when (rawCoordType) {
                    AMapLocation.COORD_TYPE_GCJ02,
                    "GCJ02" -> Gcj02.gcj02ToWgs84(rawLat, rawLon)

                    AMapLocation.COORD_TYPE_WGS84,
                    "WGS84" -> rawLat to rawLon

                    else -> rawLat to rawLon
                }

                val poiName = loc.poiName?.takeIf { it.isNotBlank() }
                    ?: loc.description?.takeIf { it.isNotBlank() }

                val address = loc.address?.takeIf { it.isNotBlank() }

                _uiState.update { old ->
                    old.copy(
                        isLoadingLocation = false,

                        // 最终入库统一使用 WGS84。
                        longitude = wgsLon,
                        latitude = wgsLat,
                        coordType = "WGS84",

                        // 保留高德原始结果用于调试。
                        rawLongitude = rawLon,
                        rawLatitude = rawLat,
                        rawCoordType = rawCoordType ?: "-",

                        poiName = poiName,
                        address = address,

                        // 仅在用户还没填写站名时自动补齐。
                        cellName = if (old.cellName.isBlank()) {
                            poiName ?: old.cellName
                        } else {
                            old.cellName
                        },

                        errorMessage = null
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoadingLocation = false,
                        errorMessage = e.message ?: appContext.getString(R.string.follow_record_location_failed)
                    )
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun getCurrentLocationByAmap(): AMapLocation {
        return suspendCancellableCoroutine { cont ->
            val client = AMapLocationClient(appContext)

            val option = AMapLocationClientOption().apply {
                locationMode = AMapLocationClientOption.AMapLocationMode.Hight_Accuracy
                isOnceLocation = true
                isOnceLocationLatest = true
                isNeedAddress = true
                httpTimeOut = 15000
                isLocationCacheEnable = true
            }

            client.setLocationOption(option)

            client.setLocationListener { loc ->
                try {
                    if (loc == null) {
                        if (cont.isActive) {
                            cont.resumeWith(
                                Result.failure(
                                    IllegalStateException(
                                        appContext.getString(R.string.follow_record_amap_location_null)
                                    )
                                )
                            )
                        }
                        return@setLocationListener
                    }

                    if (loc.errorCode == 0) {
                        if (cont.isActive) {
                            cont.resume(loc)
                        }
                    } else {
                        val msg = buildString {
                            append(appContext.getString(R.string.follow_record_amap_location_failed_prefix))
                            append(", code=").append(loc.errorCode)
                            append(", msg=").append(loc.errorInfo ?: "unknown")
                            loc.locationDetail?.takeIf { it.isNotBlank() }?.let {
                                append(", detail=").append(it)
                            }
                        }
                        if (cont.isActive) {
                            cont.resumeWith(Result.failure(IllegalStateException(msg)))
                        }
                    }
                } finally {
                    runCatching {
                        client.stopLocation()
                        client.onDestroy()
                    }
                }
            }

            client.startLocation()

            cont.invokeOnCancellation {
                runCatching {
                    client.stopLocation()
                    client.onDestroy()
                }
            }
        }
    }

    fun submit(panelState: NetworkPanelUiState) {
        viewModelScope.launch {
            val cellName = _uiState.value.cellName.trim()
            val longitude = _uiState.value.longitude
            val latitude = _uiState.value.latitude
            val siteType = _uiState.value.selectedSiteType?.serverValue

            if (cellName.isBlank()) {
                _uiState.update {
                    it.copy(errorMessage = appContext.getString(R.string.follow_record_input_cell_name))
                }
                return@launch
            }

            if (longitude == null || latitude == null) {
                _uiState.update {
                    it.copy(errorMessage = appContext.getString(R.string.follow_record_load_location_first))
                }
                return@launch
            }

            _uiState.update {
                it.copy(
                    isSubmitting = true,
                    errorMessage = null,
                    submitMessage = null,
                    submitSuccess = false
                )
            }

            val result = when (panelState.cellType.uppercase()) {
                "LTE" -> submitLte(panelState, cellName, longitude, latitude, siteType)
                "NR" -> submitNr(panelState, cellName, longitude, latitude, siteType)
                else -> UploadResult(
                    isSuccess = false,
                    error = appContext.getString(R.string.follow_record_submit_support_lte_nr)
                )
            }

            if (result.isSuccess) {
                _uiState.update {
                    it.copy(
                        isSubmitting = false,
                        submitSuccess = true,
                        submitMessage = result.message,
                        errorMessage = null
                    )
                }
            } else {
                _uiState.update {
                    it.copy(
                        isSubmitting = false,
                        submitSuccess = false,
                        submitMessage = null,
                        errorMessage = result.error
                            ?: appContext.getString(R.string.follow_record_submit_failed)
                    )
                }
            }
        }
    }

    private suspend fun submitLte(
        state: NetworkPanelUiState,
        cellName: String,
        longitude: Double,
        latitude: Double,
        siteType: String?
    ): UploadResult {
        val tac = state.tac.trim().toIntOrNull()
        val pci = state.pci.trim().toIntOrNull()
        val earfcn = state.arfcn.trim().toIntOrNull()
        val eci = state.ci.trim().toLongOrNull()

        if (tac == null || pci == null || earfcn == null || eci == null) {
            return UploadResult(
                isSuccess = false,
                error = appContext.getString(R.string.follow_record_lte_incomplete)
            )
        }

        val enodebId = (eci / 256L).toInt()
        val localCellId = (eci % 256L).toInt()

        val req = LteBatchUpsertReq(
            source = DEFAULT_SOURCE,
            items = listOf(
                LteCellParamUploadItem(
                    tac = tac,
                    pci = pci,
                    enodebId = enodebId,
                    earfcn = earfcn,
                    cellId = localCellId,
                    eci = eci,
                    localCellId = localCellId,
                    cellName = cellName,
                    sectorId = localCellId,
                    longitude = longitude,
                    latitude = latitude,
                    siteType = siteType,
                    source = DEFAULT_SOURCE
                )
            )
        )

        return when (val result = safeApiCall { apiService.batchUpsertLteCellParams(req) }) {
            is ApiResult.Ok -> UploadResult(
                isSuccess = true,
                message = buildUploadMessage(result.data)
            )
            is ApiResult.HttpError -> UploadResult(
                isSuccess = false,
                error = "HTTP ${result.code}${result.body?.let { " - $it" } ?: ""}"
            )
            is ApiResult.NetworkError -> UploadResult(
                isSuccess = false,
                error = result.message
            )
        }
    }

    private suspend fun submitNr(
        state: NetworkPanelUiState,
        cellName: String,
        longitude: Double,
        latitude: Double,
        siteType: String?
    ): UploadResult {
        val gcellId = state.ci.trim().takeIf { it.isNotBlank() && it != "-" }
        val nrTac = state.tac.trim().toIntOrNull()
        val nrPci = state.pci.trim().toIntOrNull()
        val nrArfcn = state.arfcn.trim().toIntOrNull()

        if (gcellId == null || nrTac == null || nrPci == null || nrArfcn == null) {
            return UploadResult(
                isSuccess = false,
                error = appContext.getString(R.string.follow_record_nr_incomplete)
            )
        }

        val req = NrBatchUpsertReq(
            source = DEFAULT_SOURCE,
            items = listOf(
                NrCellParamUploadItem(
                    gcellId = gcellId,
                    cellName = cellName,
                    longitude = longitude,
                    latitude = latitude,
                    nrPci = nrPci,
                    nrArfcn = nrArfcn,
                    nrTac = nrTac,
                    siteType = siteType,
                    source = DEFAULT_SOURCE
                )
            )
        )

        return when (val result = safeApiCall { apiService.batchUpsertNrCellParams(req) }) {
            is ApiResult.Ok -> UploadResult(
                isSuccess = true,
                message = buildUploadMessage(result.data)
            )
            is ApiResult.HttpError -> UploadResult(
                isSuccess = false,
                error = "HTTP ${result.code}${result.body?.let { " - $it" } ?: ""}"
            )
            is ApiResult.NetworkError -> UploadResult(
                isSuccess = false,
                error = result.message
            )
        }
    }

    private fun buildUploadMessage(resp: BatchUpsertResp): String {
        val affected = resp.affectedRows?.let {
            appContext.getString(R.string.follow_record_upload_affected_rows, it)
        }.orEmpty()
        return appContext.getString(
            R.string.follow_record_upload_message,
            resp.received,
            resp.success,
            resp.skipped,
            affected
        )
    }

    private fun techCodeOf(panelState: NetworkPanelUiState): String? {
        return when (panelState.cellType.uppercase()) {
            "NR" -> "5"
            "LTE" -> "L"
            else -> null
        }
    }

    private fun buildBaseCellName(current: String, poiName: String?): String {
        val source = current.trim().ifBlank { poiName?.trim().orEmpty() }
        return source.replace(SUFFIX_REGEX, "").trim().trimEnd('-')
    }
}

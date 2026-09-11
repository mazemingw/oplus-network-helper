package com.nvmex.networkhelper.ui.network.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material3.LocalTextStyle
import androidx.hilt.navigation.compose.hiltViewModel
import com.amap.api.location.AMapLocation
import com.nvmex.networkhelper.network.model.LteCellAdminDeleteReq
import com.nvmex.networkhelper.network.model.LteCellAdminUpdateReq
import com.nvmex.networkhelper.network.model.NrCellAdminDeleteReq
import com.nvmex.networkhelper.network.model.NrCellAdminUpdateReq
import com.nvmex.networkhelper.ui.base.PanelDivider
import com.nvmex.networkhelper.ui.map.AmapMapActivity
import com.nvmex.networkhelper.util.base.formatDateTimeUtc8
import com.nvmex.networkhelper.util.home.DeveloperModePrefs
import com.nvmex.networkhelper.util.map.Gcj02
import com.nvmex.networkhelper.util.network.cellMatchLevelText
import com.nvmex.networkhelper.viewmodel.menu.CellQueryUiState
import com.nvmex.networkhelper.viewmodel.signal.AmapLocator
import com.nvmex.networkhelper.viewmodel.signal.CellAdminViewModel
import kotlin.math.*

private data class DialogLocationState(
    val isLoading: Boolean = false,
    val errorMessage: String? = null,

    val rawLat: Double? = null,
    val rawLon: Double? = null,
    val rawCoordType: String? = null,

    val currentWgsLat: Double? = null,
    val currentWgsLon: Double? = null,

    val currentGcjLat: Double? = null,
    val currentGcjLon: Double? = null,

    val poiName: String? = null,
    val address: String? = null,

    val distanceWgsMeters: Double? = null,
    val distanceGcjMeters: Double? = null
)

@Composable
fun LteCellDetailDialog(
    show: Boolean,
    queryUi: CellQueryUiState,
    onDismiss: () -> Unit,
    onCellChanged: (() -> Unit)? = null
) {
    if (!show) return

    val context = LocalContext.current
    val adminVm: CellAdminViewModel = hiltViewModel()
    val adminUi by adminVm.uiState.collectAsState()
    val isSuperAdmin = remember(show) { DeveloperModePrefs.isSuperAdmin(context) }
    val authToken = remember(show) { DeveloperModePrefs.getAuthToken(context).trim() }
    val authTokenValid = remember(show) { DeveloperModePrefs.isAuthTokenValid(context) }

    LaunchedEffect(adminUi.lastActionAt) {
        val actionAt = adminUi.lastActionAt ?: return@LaunchedEffect
        if (actionAt > 0L) {
            onCellChanged?.invoke()
            if (adminUi.lastAction?.startsWith("delete_") == true) {
                onDismiss()
            }
            adminVm.consumeActionEvent()
        }
    }

    fun fmt(v: Any?): String = when (v) {
        null -> "-"
        is Double -> String.format("%.6f", v)
        is Float -> String.format("%.6f", v)
        else -> v.toString()
    }

    fun fmtDistance(meters: Double?): String {
        if (meters == null) return "-"
        return if (meters >= 1000) {
            String.format("%.2f km", meters / 1000.0)
        } else {
            String.format("%.0f m", meters)
        }
    }

    val cellCoord = remember(queryUi) {
        when (queryUi) {
            is CellQueryUiState.LteSuccess -> {
                val first = queryUi.resp.data.firstOrNull()
                first?.longitude?.let { lon ->
                    first.latitude?.let { lat -> lon to lat }
                }
            }

            is CellQueryUiState.NrSuccess -> {
                val first = queryUi.resp.data.firstOrNull()
                first?.longitude?.let { lon ->
                    first.latitude?.let { lat -> lon to lat }
                }
            }

            else -> null
        }
    }

    var reloadToken by remember(show, queryUi) { mutableStateOf(0) }

    val locationState by produceState(
        initialValue = DialogLocationState(),
        key1 = show,
        key2 = cellCoord,
        key3 = reloadToken
    ) {
        if (!show || cellCoord == null) {
            value = DialogLocationState()
            return@produceState
        }

        val (cellLonWgs, cellLatWgs) = cellCoord!!
        value = DialogLocationState(isLoading = true)

        val result = runCatching {
            AmapLocator(context.applicationContext).getCurrentLocation().getOrThrow()
        }

        value = result.fold(
            onSuccess = { loc ->
                val rawLat = loc.latitude
                val rawLon = loc.longitude
                val rawCoordType = loc.coordType?.trim()?.uppercase()

                val (currentWgsLat, currentWgsLon) = when (rawCoordType) {
                    AMapLocation.COORD_TYPE_GCJ02,
                    "GCJ02" -> Gcj02.gcj02ToWgs84(rawLat, rawLon)

                    AMapLocation.COORD_TYPE_WGS84,
                    "WGS84" -> rawLat to rawLon

                    else -> rawLat to rawLon
                }

                val (currentGcjLat, currentGcjLon) = when (rawCoordType) {
                    AMapLocation.COORD_TYPE_GCJ02,
                    "GCJ02" -> rawLat to rawLon

                    AMapLocation.COORD_TYPE_WGS84,
                    "WGS84" -> Gcj02.wgs84ToGcj02(rawLat, rawLon)

                    else -> Gcj02.wgs84ToGcj02(rawLat, rawLon)
                }

                val (cellGcjLat, cellGcjLon) = Gcj02.wgs84ToGcj02(cellLatWgs, cellLonWgs)

                val distanceWgsMeters = haversineMeters(
                    lat1 = cellLatWgs,
                    lon1 = cellLonWgs,
                    lat2 = currentWgsLat,
                    lon2 = currentWgsLon
                )

                val distanceGcjMeters = haversineMeters(
                    lat1 = cellGcjLat,
                    lon1 = cellGcjLon,
                    lat2 = currentGcjLat,
                    lon2 = currentGcjLon
                )

                DialogLocationState(
                    isLoading = false,
                    errorMessage = null,
                    rawLat = rawLat,
                    rawLon = rawLon,
                    rawCoordType = rawCoordType ?: "-",
                    currentWgsLat = currentWgsLat,
                    currentWgsLon = currentWgsLon,
                    currentGcjLat = currentGcjLat,
                    currentGcjLon = currentGcjLon,
                    poiName = loc.poiName,
                    address = loc.address,
                    distanceWgsMeters = distanceWgsMeters,
                    distanceGcjMeters = distanceGcjMeters
                )
            },
            onFailure = {
                DialogLocationState(
                    isLoading = false,
                    errorMessage = it.message ?: "定位失败"
                )
            }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    "确定",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = { reloadToken++ },
                enabled = !locationState.isLoading && cellCoord != null
            ) {
                Text(
                    if (locationState.isLoading) "定位中..." else "重新定位",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
        },
        title = {
            Text(
                when (queryUi) {
                    is CellQueryUiState.LteSuccess -> "小区详情（LTE 工参）"
                    is CellQueryUiState.NrSuccess -> "小区详情（NR 工参）"
                    else -> "小区详情"
                },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            CompositionLocalProvider(LocalTextStyle provides MaterialTheme.typography.bodySmall) {
                when (queryUi) {
                is CellQueryUiState.LteSuccess -> {
                    val resp = queryUi.resp
                    val first = resp.data.firstOrNull()

                    if (first == null) {
                        Text("无可展示数据")
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 560.dp)
                        ) {
                            Column(
                                modifier = Modifier.verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text(
                                    text = "匹配等级：${cellMatchLevelText(resp.match_level)} · 查到：${resp.count} 条",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                PanelDivider()

                                Text(
                                    "标识信息",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        "基站/小区名称：${first.cell_name ?: "-"}",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text("ECI（长号）：${fmt(first.eci)}")
                                    Text("eNodeB ID：${fmt(first.enodeb_id)}")
                                    Text("Cell ID（短号）：${fmt(first.cell_id)}")
                                    Text("PCI：${fmt(first.pci)}")
                                    Text("TAC：${fmt(first.tac)}")
                                    Text("EARFCN：${fmt(first.earfcn)}")
                                    Text("扇区号（Sector ID）：${fmt(first.sector_id)}")
                                }

                                PanelDivider()

                                Text(
                                    "位置信息",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    val lon = first.longitude
                                    val lat = first.latitude
                                    val hasCoord = lon != null && lat != null

                                    val (gcjLat, gcjLon) = if (hasCoord) {
                                        Gcj02.wgs84ToGcj02(lat!!, lon!!)
                                    } else {
                                        null to null
                                    }

                                    Text("基站原始坐标（WGS84）：${if (hasCoord) "${fmt(lon)}, ${fmt(lat)}" else "-"}")
                                    Text("基站转换坐标（GCJ02）：${if (hasCoord) "${fmt(gcjLon)}, ${fmt(gcjLat)}" else "-"}")

                                    InnerMapTestButtons(
                                        hasCoord = hasCoord,
                                        distanceWgsMeters = locationState.distanceWgsMeters,
                                        distanceGcjMeters = locationState.distanceGcjMeters,
                                        onOpenRaw = {
                                            context.startActivity(
                                                AmapMapActivity.createIntentForWgs84(
                                                    context = context,
                                                    lat = lat!!,
                                                    lon = lon!!,
                                                    title = first.cell_name ?: "基站"
                                                )
                                            )
                                        },
                                        onOpenGcj = {
                                            context.startActivity(
                                                AmapMapActivity.createIntentForGcj02(
                                                    context = context,
                                                    lat = lat!!,
                                                    lon = lon!!,
                                                    title = first.cell_name ?: "基站"
                                                )
                                            )
                                        }
                                    )

                                    Text(
                                        "方位角（Azimuth）：${fmt(first.azimuth)}°",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }

                                PanelDivider()

                                CurrentLocationDistanceBlock(
                                    state = locationState
                                )

                                PanelDivider()

                                Text(
                                    "站点信息",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("站型：${first.site_type ?: "-"}")
                                    Text("天线挂高：${fmt(first.antenna_height)} m")
                                    Text("数据来源：${first.source ?: "-"}")
                                }

                                SuperAdminEditorSection(
                                    enabled = isSuperAdmin,
                                    authToken = authToken,
                                    authTokenValid = authTokenValid,
                                    busy = adminUi.isSubmitting,
                                    apiError = adminUi.errorMessage,
                                    apiSuccess = adminUi.successMessage,
                                    recordKey = "LTE:${first.id ?: -1L}",
                                    rowId = first.id,
                                    cellName = first.cell_name,
                                    siteType = first.site_type,
                                    azimuth = first.azimuth,
                                    antennaHeight = first.antenna_height,
                                    longitude = first.longitude,
                                    latitude = first.latitude,
                                    onClearMessages = { adminVm.clearMessages() },
                                    onUpdate = { id, cName, sType, az, antH, lonV, latV ->
                                        adminVm.updateLte(
                                            LteCellAdminUpdateReq(
                                                auth_token = authToken,
                                                id = id,
                                                eci = first.eci,
                                                cell_name = cName,
                                                site_type = sType,
                                                azimuth = az,
                                                antenna_height = antH,
                                                longitude = lonV,
                                                latitude = latV
                                            )
                                        )
                                    },
                                    onDelete = { id ->
                                        adminVm.deleteLte(
                                            LteCellAdminDeleteReq(
                                                auth_token = authToken,
                                                id = id,
                                                eci = first.eci
                                            )
                                        )
                                    }
                                )
                            }
                        }
                    }
                }

                is CellQueryUiState.NrSuccess -> {
                    val resp = queryUi.resp
                    val first = resp.data.firstOrNull()

                    if (first == null) {
                        Text("无可展示数据")
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 560.dp)
                        ) {
                            Column(
                                modifier = Modifier.verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text(
                                    text = "匹配等级：${cellMatchLevelText(resp.match_level)} · 查到：${resp.count} 条",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                if (resp.truncated) {
                                    Text(
                                        text = "结果已截断，当前仅展示部分记录。",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }

                                if (!resp.reason.isNullOrBlank()) {
                                    Text(
                                        text = "说明：${resp.reason}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                PanelDivider()

                                Text(
                                    "标识信息",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("基站/小区名称：${first.cell_name ?: "-"}", fontWeight = FontWeight.SemiBold)
                                    Text("GCell ID：${fmt(first.gcell_id)}")
                                    Text("NR PCI：${fmt(first.nr_pci)}")
                                    Text("NR TAC：${fmt(first.nr_tac)}")
                                    Text("NR ARFCN：${fmt(first.nr_arfcn)}")
                                }

                                PanelDivider()

                                Text(
                                    "位置信息",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    val lon = first.longitude
                                    val lat = first.latitude
                                    val hasCoord = lon != null && lat != null

                                    val (gcjLat, gcjLon) = if (hasCoord) {
                                        Gcj02.wgs84ToGcj02(lat!!, lon!!)
                                    } else {
                                        null to null
                                    }

                                    Text("基站原始坐标（WGS84）：${if (hasCoord) "${fmt(lon)}, ${fmt(lat)}" else "-"}")
                                    Text("基站转换坐标（GCJ02）：${if (hasCoord) "${fmt(gcjLon)}, ${fmt(gcjLat)}" else "-"}")

                                    InnerMapTestButtons(
                                        hasCoord = hasCoord,
                                        distanceWgsMeters = locationState.distanceWgsMeters,
                                        distanceGcjMeters = locationState.distanceGcjMeters,
                                        onOpenRaw = {
                                            context.startActivity(
                                                AmapMapActivity.createIntentForWgs84(
                                                    context = context,
                                                    lat = lat!!,
                                                    lon = lon!!,
                                                    title = first.cell_name ?: "基站"
                                                )
                                            )
                                        },
                                        onOpenGcj = {
                                            context.startActivity(
                                                AmapMapActivity.createIntentForGcj02(
                                                    context = context,
                                                    lat = lat!!,
                                                    lon = lon!!,
                                                    title = first.cell_name ?: "基站"
                                                )
                                            )
                                        }
                                    )

                                    Text(
                                        "方位角（Azimuth）：${fmt(first.azimuth)}°",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }

                                PanelDivider()

                                CurrentLocationDistanceBlock(
                                    state = locationState
                                )

                                PanelDivider()

                                Text(
                                    "站点信息",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("站型：${first.site_type ?: "-"}")
                                    Text("天线挂高：${fmt(first.antenna_height)} m")
                                    Text("数据来源：${first.source ?: "-"}")
                                    Text("创建时间：${formatDateTimeUtc8(first.created_at)}")
                                    Text("更新时间：${formatDateTimeUtc8(first.updated_at)}")
                                }

                                SuperAdminEditorSection(
                                    enabled = isSuperAdmin,
                                    authToken = authToken,
                                    authTokenValid = authTokenValid,
                                    busy = adminUi.isSubmitting,
                                    apiError = adminUi.errorMessage,
                                    apiSuccess = adminUi.successMessage,
                                    recordKey = "NR:${first.id ?: -1L}",
                                    rowId = first.id,
                                    cellName = first.cell_name,
                                    siteType = first.site_type,
                                    azimuth = first.azimuth,
                                    antennaHeight = first.antenna_height,
                                    longitude = first.longitude,
                                    latitude = first.latitude,
                                    onClearMessages = { adminVm.clearMessages() },
                                    onUpdate = { id, cName, sType, az, antH, lonV, latV ->
                                        adminVm.updateNr(
                                            NrCellAdminUpdateReq(
                                                auth_token = authToken,
                                                id = id,
                                                gcell_id = first.gcell_id,
                                                cell_name = cName,
                                                site_type = sType,
                                                azimuth = az,
                                                antenna_height = antH,
                                                longitude = lonV,
                                                latitude = latV
                                            )
                                        )
                                    },
                                    onDelete = { id ->
                                        adminVm.deleteNr(
                                            NrCellAdminDeleteReq(
                                                auth_token = authToken,
                                                id = id,
                                                gcell_id = first.gcell_id
                                            )
                                        )
                                    }
                                )
                            }
                        }
                    }
                }

                CellQueryUiState.Idle -> Text("暂无查询结果")
                CellQueryUiState.Loading -> Text("正在查询中…")
                is CellQueryUiState.Error -> Text("查询失败：${queryUi.msg}")
            }
            }
        }
    )
}

@Composable
private fun SuperAdminEditorSection(
    enabled: Boolean,
    authToken: String,
    authTokenValid: Boolean,
    busy: Boolean,
    apiError: String?,
    apiSuccess: String?,
    recordKey: String,
    rowId: Long?,
    cellName: String?,
    siteType: String?,
    azimuth: Int?,
    antennaHeight: Double?,
    longitude: Double?,
    latitude: Double?,
    onClearMessages: () -> Unit,
    onUpdate: (
        id: Long?,
        cellName: String?,
        siteType: String?,
        azimuth: Int?,
        antennaHeight: Double?,
        longitude: Double?,
        latitude: Double?
    ) -> Unit,
    onDelete: (id: Long?) -> Unit
) {
    if (!enabled) return

    var editMode by rememberSaveable(recordKey) { mutableStateOf(false) }
    var confirmDelete by rememberSaveable(recordKey) { mutableStateOf(false) }
    var localError by rememberSaveable(recordKey) { mutableStateOf<String?>(null) }

    var editCellName by rememberSaveable(recordKey) { mutableStateOf(cellName.orEmpty()) }
    var editSiteType by rememberSaveable(recordKey) { mutableStateOf(siteType.orEmpty()) }
    var editAzimuth by rememberSaveable(recordKey) { mutableStateOf(azimuth?.toString().orEmpty()) }
    var editAntennaHeight by rememberSaveable(recordKey) { mutableStateOf(antennaHeight?.toString().orEmpty()) }
    var editLongitude by rememberSaveable(recordKey) { mutableStateOf(longitude?.toString().orEmpty()) }
    var editLatitude by rememberSaveable(recordKey) { mutableStateOf(latitude?.toString().orEmpty()) }

    fun parseIntInput(raw: String, fieldName: String): Int? {
        val t = raw.trim()
        if (t.isBlank()) return null
        return t.toIntOrNull() ?: run {
            localError = "$fieldName 格式不正确"
            null
        }
    }

    fun parseDoubleInput(raw: String, fieldName: String): Double? {
        val t = raw.trim()
        if (t.isBlank()) return null
        return t.toDoubleOrNull() ?: run {
            localError = "$fieldName 格式不正确"
            null
        }
    }

    PanelDivider()

    Text(
        text = "管理操作（超级管理员）",
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold
    )

    if (!authTokenValid || authToken.isBlank()) {
        Text(
            text = "鉴权已过期，请重新在开发者模式验证密码",
            color = MaterialTheme.colorScheme.error
        )
    }

    if (rowId == null) {
        Text(
            text = "当前记录缺少服务端主键，将自动使用后备标识定位",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    if (editMode) {
        OutlinedTextField(
            value = editCellName,
            onValueChange = { editCellName = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("基站名称") }
        )

        OutlinedTextField(
            value = editSiteType,
            onValueChange = { editSiteType = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("站型") }
        )

        OutlinedTextField(
            value = editAzimuth,
            onValueChange = { editAzimuth = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("方位角") }
        )

        OutlinedTextField(
            value = editAntennaHeight,
            onValueChange = { editAntennaHeight = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("天线挂高/高度(m)") }
        )

        OutlinedTextField(
            value = editLongitude,
            onValueChange = { editLongitude = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("经度") }
        )

        OutlinedTextField(
            value = editLatitude,
            onValueChange = { editLatitude = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("纬度") }
        )
    }

    localError?.takeIf { it.isNotBlank() }?.let {
        Text(
            text = it,
            color = MaterialTheme.colorScheme.error
        )
    }
    apiError?.takeIf { it.isNotBlank() }?.let {
        Text(
            text = it,
            color = MaterialTheme.colorScheme.error
        )
    }
    apiSuccess?.takeIf { it.isNotBlank() }?.let {
        Text(
            text = it,
            color = MaterialTheme.colorScheme.primary
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (!editMode) {
            TextButton(
                enabled = !busy && authTokenValid && authToken.isNotBlank(),
                onClick = {
                    onClearMessages()
                    localError = null
                    editMode = true
                }
            ) {
                Text("编辑")
            }
        }

        if (editMode) {
            TextButton(
                enabled = !busy && authTokenValid && authToken.isNotBlank(),
                onClick = {
                    onClearMessages()
                    localError = null

                    val parsedAz = parseIntInput(editAzimuth, "方位角")
                    if (editAzimuth.trim().isNotBlank() && parsedAz == null) return@TextButton

                    val parsedAntennaHeight = parseDoubleInput(editAntennaHeight, "天线挂高")
                    if (editAntennaHeight.trim().isNotBlank() && parsedAntennaHeight == null) return@TextButton

                    val parsedLon = parseDoubleInput(editLongitude, "经度")
                    if (editLongitude.trim().isNotBlank() && parsedLon == null) return@TextButton

                    val parsedLat = parseDoubleInput(editLatitude, "纬度")
                    if (editLatitude.trim().isNotBlank() && parsedLat == null) return@TextButton

                    onUpdate(
                        rowId,
                        editCellName.trim().ifBlank { null },
                        editSiteType.trim().ifBlank { null },
                        parsedAz,
                        parsedAntennaHeight,
                        parsedLon,
                        parsedLat
                    )
                    editMode = false
                }
            ) {
                Text(if (busy) "保存中..." else "保存")
            }

            TextButton(
                enabled = !busy,
                onClick = {
                    onClearMessages()
                    localError = null
                    editMode = false
                    editCellName = cellName.orEmpty()
                    editSiteType = siteType.orEmpty()
                    editAzimuth = azimuth?.toString().orEmpty()
                    editAntennaHeight = antennaHeight?.toString().orEmpty()
                    editLongitude = longitude?.toString().orEmpty()
                    editLatitude = latitude?.toString().orEmpty()
                }
            ) {
                Text("取消")
            }
        }

        TextButton(
            enabled = !busy && authTokenValid && authToken.isNotBlank(),
            onClick = {
                onClearMessages()
                localError = null
                confirmDelete = true
            }
        ) {
            Text("删除")
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("确认删除") },
            text = { Text("确认删除当前基站记录吗？该操作不可撤销。") },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        confirmDelete = false
                        onDelete(rowId)
                    }
                ) {
                    Text(if (busy) "删除中..." else "删除")
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !busy,
                    onClick = { confirmDelete = false }
                ) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
private fun CurrentLocationDistanceBlock(
    state: DialogLocationState
) {
    Text("当前位置", fontWeight = FontWeight.SemiBold)

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when {
            state.isLoading -> {
                CircularProgressIndicator()
                Text("正在通过高德获取当前位置…")
            }

            !state.errorMessage.isNullOrBlank() -> {
                Text(
                    text = state.errorMessage,
                    color = MaterialTheme.colorScheme.error
                )
            }

            else -> {
                Text("详细地址：${state.address?.ifBlank { "-" } ?: "-"}")
            }
        }
    }
}

@Composable
private fun InnerMapTestButtons(
    hasCoord: Boolean,
    distanceWgsMeters: Double?,
    distanceGcjMeters: Double?,
    onOpenRaw: () -> Unit,
    onOpenGcj: () -> Unit
) {
    fun fmtDistanceInline(meters: Double?): String {
        if (meters == null) return "-"
        return if (meters >= 1000) {
            String.format("%.2fkm", meters / 1000.0)
        } else {
            String.format("%.0fm", meters)
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilledTonalButton(
            onClick = onOpenRaw,
            enabled = hasCoord,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 8.dp)
        ) {
            Text(
                text = "打开 WGS84 坐标（${fmtDistanceInline(distanceWgsMeters)}）",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold
            )
        }

        FilledTonalButton(
            onClick = onOpenGcj,
            enabled = hasCoord,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 8.dp)
        ) {
            Text(
                text = "打开 GCJ02 坐标（${fmtDistanceInline(distanceGcjMeters)}）",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

private fun haversineMeters(
    lat1: Double,
    lon1: Double,
    lat2: Double,
    lon2: Double
): Double {
    val earthRadius = 6371000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)

    val a = sin(dLat / 2).pow(2.0) +
            cos(Math.toRadians(lat1)) *
            cos(Math.toRadians(lat2)) *
            sin(dLon / 2).pow(2.0)

    val c = 2 * atan2(sqrt(a), sqrt(1 - a))
    return earthRadius * c
}




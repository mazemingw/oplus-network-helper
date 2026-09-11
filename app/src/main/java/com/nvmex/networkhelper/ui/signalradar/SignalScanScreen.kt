package com.nvmex.networkhelper.ui.signalradar

import android.content.Context
import android.net.wifi.WifiManager
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nvmex.networkhelper.R
import com.nvmex.networkhelper.model.network.NetworkPanelUiState
import com.nvmex.networkhelper.model.signalradar.WifiReminderDataStore
import com.nvmex.networkhelper.viewmodel.signalradar.SignalScanViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.min

@Composable
fun SignalScanScreen(
    modifier: Modifier = Modifier,
    contentBottomPadding: Dp = 0.dp,
    vm: SignalScanViewModel = viewModel()
) {
    val ui by vm.ui.collectAsState()

    var scenario by remember { mutableStateOf("") }

    val scrollState = rememberScrollState()
    val context = LocalContext.current

    // 使用 WifiReminderDataStore
    val wifiReminderDataStore = remember { WifiReminderDataStore(context) }

    // ✅ 使用 produceState 并添加加载完成标志
    val wifiReminderState by produceState(initialValue = Triple(false, false, false)) {
        wifiReminderDataStore.isReminderDisabledFlow().collect { disabled ->
            value = Triple(disabled, true, false) // (是否禁用, 是否已加载, 是否已保存)
        }
    }
    val wifiReminderDisabled = wifiReminderState.first
    val isWifiReminderLoaded = wifiReminderState.second

    // 本地存储，用于记录用户是否选择"不再提示"
    val sharedPreferences = remember {
        context.getSharedPreferences("compass_prefs", Context.MODE_PRIVATE)
    }
    // 控制校准提示弹窗的显示
    var showCalibrationDialog by remember { mutableStateOf(false) }

    // 校准提示的回调函数
    val onDismiss = { showCalibrationDialog = false }
    val onConfirm = {
        showCalibrationDialog = false
        // 处理确认后逻辑
    }
    val onDontShowAgain = {
        sharedPreferences.edit().putBoolean("dont_show_compass_calibration", true).apply()
        showCalibrationDialog = false
    }

    // 首次进入时检查是否需要显示校准提示
    LaunchedEffect(Unit) {
        // 检查用户是否已经选择"不再提示"
        val dontShowAgain = sharedPreferences.getBoolean("dont_show_compass_calibration", false)
        if (!dontShowAgain) {
            // 延迟显示，让界面先加载完成
            delay(500)
            showCalibrationDialog = true
        }
    }

    // 校准提示弹窗
    if (showCalibrationDialog) {
        CompassCalibrationDialog(
            showDialog = showCalibrationDialog,
            onDismiss = onDismiss,
            onConfirm = onConfirm,
            onDontShowAgain = onDontShowAgain
        )
    }

    // 获取 WiFi 状态
    val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
    val wifiStatus = wifiManager.isWifiEnabled

    // WiFi 提醒弹窗状态
    var showWifiDialog by rememberSaveable { mutableStateOf(false) }

    // WiFi 开启且未禁用提醒时显示弹窗（只有加载完成后才判断）
    LaunchedEffect(wifiStatus, wifiReminderDisabled, isWifiReminderLoaded) {
        if (isWifiReminderLoaded) {
            showWifiDialog = wifiStatus && !wifiReminderDisabled
        } else {
            showWifiDialog = false // 加载完成前不显示
        }
    }

    // WiFi 提醒弹窗
    if (showWifiDialog) {
        AlertDialog(
            onDismissRequest = { showWifiDialog = false },
            title = { Text(stringResource(R.string.radar_wifi_dialog_title)) },
            text = { Text(stringResource(R.string.radar_wifi_dialog_body)) },

            // 确认按钮：我关闭了
            confirmButton = {
                Button(
                    onClick = {
                        showWifiDialog = false
                    },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(stringResource(R.string.radar_wifi_closed), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                }
            },

            // 取消按钮区域：先不管 + 不再提示
            dismissButton = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 先不管按钮
                    TextButton(
                        onClick = { showWifiDialog = false }
                    ) {
                        Text(stringResource(R.string.radar_ignore_now), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    }

                    // 不再提示按钮
                    TextButton(
                        onClick = {
                            // 保存"不再提示"状态
                            CoroutineScope(Dispatchers.IO).launch {
                                wifiReminderDataStore.setReminderDisabled(true)
                            }
                            showWifiDialog = false
                        }
                    ) {
                        Text(
                            text = stringResource(R.string.radar_dont_remind),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        )
    }

    CompositionLocalProvider(LocalTextStyle provides MaterialTheme.typography.bodySmall) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(
                        start = 18.dp,
                        end = 18.dp,
                        bottom = 18.dp + contentBottomPadding
                    ),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {

                val sims = ui.sims
                val selectedIndex = sims.indexOfFirst { it.subId == ui.selectedSubId }.coerceAtLeast(0)
                if (sims.isNotEmpty()) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                                shape = MaterialTheme.shapes.large
                        ),
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.42f),
                        tonalElevation = 0.dp,
                        shadowElevation = 0.dp
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            ScrollableTabRow(
                                selectedTabIndex = selectedIndex,
                                edgePadding = 6.dp,
                                containerColor = Color.Transparent,
                                contentColor = MaterialTheme.colorScheme.primary,
                                divider = {},
                                indicator = { tabPositions ->
                                    TabRowDefaults.Indicator(
                                        modifier = Modifier.tabIndicatorOffset(tabPositions[selectedIndex]),
                                        height = 3.dp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            ) {
                                sims.forEachIndexed { idx, sim ->
                                    Tab(
                                        selected = idx == selectedIndex,
                                        enabled = !ui.running,
                                        onClick = { vm.selectSim(sim.subId) },
                                        text = {
                                            Text(
                                                text = "SIM${sim.slotIndex + 1}",
                                                style = MaterialTheme.typography.labelMedium
                                            )
                                        },
                                        unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            if (ui.running) {
                                Text(
                                    text = stringResource(R.string.radar_switch_sim_disabled),
                                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

            // ✅ 顶部信号条条（主页同款）
            SignalBarsCard(s = ui.lastSignal)

        // ✅ 目标 + 站桩进度（3s）
        val targetCenterDeg = (ui.targetBin * 30) % 360
        val dwellFrac = if (ui.dwellNeedMs <= 0L) 0f else (ui.dwellMs.toFloat() / ui.dwellNeedMs).coerceIn(0f, 1f)
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = if (ui.running) {
                        stringResource(R.string.radar_target_sector, ui.targetBin, targetCenterDeg)
                    } else {
                        stringResource(R.string.radar_not_started)
                    },
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.labelLarge
                )
                LinearProgressIndicator(progress = { dwellFrac }, modifier = Modifier.fillMaxWidth())
                Text(
                    text = if (ui.running) {
                        stringResource(
                            R.string.radar_dwell_hint,
                            ((ui.dwellNeedMs - ui.dwellMs).coerceAtLeast(0L) / 1000f).toString()
                        )
                    } else {
                        ui.progressText.ifBlank { stringResource(R.string.radar_not_started) }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (!ui.error.isNullOrBlank()) {
            Text("⚠ ${ui.error}", color = MaterialTheme.colorScheme.error)
        }

        // 基本信息
        val operatorInfo = "${ui.lastSignal.operatorName} ${ui.lastSignal.cellType} |  Band: ${ui.lastSignal.band}"
        Text(" $operatorInfo")

        // 双层伞形图：外圈 RSRP / 内圈 SINR
        val cfg = LocalConfiguration.current
        val chartH = (cfg.screenHeightDp * 0.46f).dp

        UmbrellaChart12(
            modifier = Modifier
                .fillMaxWidth()
                .height(chartH),
            binsRsrp = ui.binsPreviewRsrp,
            binsSinr = ui.binsPreviewSinr,
            headingDeg = ui.headingDeg,
            coveredBins = ui.coveredBins,
            targetBin = ui.targetBin
        )

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = { vm.start() },
                enabled = !ui.running,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) { Text(stringResource(R.string.radar_start_scan), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold) }

            OutlinedButton(
                onClick = {
                    // 将运营商、网络制式、Band 信息传递给 stopAndBuildSession
                    vm.stopAndBuildSession(scenario, ui.lastSignal.operatorName, ui.lastSignal.cellType, ui.lastSignal.band)
                },
                enabled = ui.running,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) { Text(stringResource(R.string.radar_finish_generate), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold) }

        }

        OutlinedTextField(
            value = scenario,
            onValueChange = { scenario = it },
            label = { Text(stringResource(R.string.radar_scenario_name), style = MaterialTheme.typography.bodySmall) },
            modifier = Modifier.fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodySmall,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)
        )

        ui.lastSession?.let { s ->
            Divider()
            Text(stringResource(R.string.radar_generated, s.scenarioName, s.samplesCount))
            Text(stringResource(R.string.radar_time_range, vm.formatTs(s.startedAt), vm.formatTs(s.endedAt)))

            ui.lastSavedUri?.let {
                Text(
                    stringResource(R.string.radar_saved_to_download),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Divider()

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(R.string.radar_local_records), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
            TextButton(onClick = { vm.refreshSavedList() }) { Text(stringResource(R.string.radar_refresh), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold) }
        }

        var showJsonDialog by remember { mutableStateOf(false) }
        var jsonTitle by remember { mutableStateOf("") }
        var jsonBody by remember { mutableStateOf("") }

        if (ui.savedList.isEmpty()) {
            Text(
                stringResource(R.string.radar_no_records),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            ui.savedList.take(6).forEach { item ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(
                        Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(item.displayName, fontWeight = FontWeight.SemiBold)
                        Text(
                            "${vm.formatTs(item.createdAt)} · ${(item.sizeBytes / 1024)} KB",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = { vm.loadSessionFromUri(item.uri) },
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) { Text(stringResource(R.string.radar_replay), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold) }

                            OutlinedButton(
                                onClick = { vm.deleteSaved(item.uri) },
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error
                                ),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) { Text(stringResource(R.string.action_delete), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold) }
                        }
                    }
                }

            }
        }

            if (showJsonDialog) {
                AlertDialog(
                    onDismissRequest = { showJsonDialog = false },
                    confirmButton = { TextButton(onClick = { showJsonDialog = false }) { Text(stringResource(R.string.action_close), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold) } },
                    title = { Text(jsonTitle) },
                    text = { Text(jsonBody, style = MaterialTheme.typography.bodySmall) }
                )
            }

            }
        }
    }
}




/**
 * 顶部信号条条（复用你主页的表现：NR 用 SS 三件套，LTE 用 RSRP/RSRQ/SINR）
 */
@Composable
private fun SignalBarsCard(s: NetworkPanelUiState) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val title = if (s.cellType == "NR") "NR（SS）" else "LTE"
            Text(title, fontWeight = FontWeight.Bold)

            if (s.cellType == "NR") {
                SignalBarRow("SS-RSRP", s.ssRsrp, fractionFromDbm(s.ssRsrp, -140.0, -70.0))
                SignalBarRow("SS-RSRQ", s.ssRsrq, fractionFromDb(s.ssRsrq, -20.0, -3.0))
                SignalBarRow("SS-SINR", s.ssSinr, fractionFromDb(s.ssSinr, -10.0, 30.0))
            } else {
                SignalBarRow("RSRP", s.rsrp, fractionFromDbm(s.rsrp, -140.0, -70.0))
                SignalBarRow("RSRQ", s.rsrq, fractionFromDb(s.rsrq, -20.0, -3.0))
                SignalBarRow("SINR", s.sinr, fractionFromDb(s.sinr, -10.0, 30.0))
            }
        }
    }
}

@Composable
private fun SignalBarRow(label: String, valueText: String, fraction: Float) {
    val hasValue = valueText.isNotBlank() && valueText != "-"
    val safeFraction = fraction.coerceIn(0f, 1f)

    // 目标宽度：没值就 0
    val target = if (hasValue) safeFraction else 0f

    // ✅ 宽度平滑过渡（避免“生硬跳变”）
    val animatedFrac by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(
            durationMillis = 420,
            easing = FastOutSlowInEasing
        ),
        label = "barWidth"
    )

    //  颜色也做过渡（避免从红->绿一帧切过去）
    val targetColor = if (!hasValue) {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)
    } else {
        qualityColor(safeFraction)
    }
    val animatedColor by animateColorAsState(
        targetValue = targetColor,
        animationSpec = tween(durationMillis = 380, easing = FastOutSlowInEasing),
        label = "barColor"
    )

    //  轻微“呼吸”：只在有值时启用，幅度非常小
    val infinite = rememberInfiniteTransition(label = "barBreath")
    val breath by infinite.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.00f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breath"
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            modifier = Modifier.width(92.dp),
            style = MaterialTheme.typography.bodyMedium
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .height(12.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f))
        ) {
            // 进度条
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(animatedFrac)
                    .clip(RoundedCornerShape(999.dp))
                    // 呼吸做在 layer 上：更“软”，而不是改颜色 alpha
                    .graphicsLayer {
                        alpha = if (hasValue) breath else 1f
                    }
                    .background(animatedColor)
            )

            // 在进度条中间画一条竖线，指代50%
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(1.dp) // 设置竖线宽度
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)) // 设置颜色
                    .align(Alignment.Center) // 使竖线居中
            )
        }

        Spacer(Modifier.width(10.dp))

        Text(
            text = valueText,
            modifier = Modifier.widthIn(min = 70.dp),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}


/**
 * 12 扇区双层伞形图
 * 外圈：RSRP(dBm)
 * 内圈：SINR(dB)
 * 文本：-92 / 18
 */
@Composable
fun UmbrellaChart12(
    modifier: Modifier,
    binsRsrp: Map<Int, Int?>,
    binsSinr: Map<Int, Float?>,
    headingDeg: Float,
    coveredBins: Set<Int>,
    targetBin: Int = -1 // 可选：高亮当前目标扇区（不传也能跑）
) {
    //  颜色必须在 Canvas 外取
    val onSurface = MaterialTheme.colorScheme.onSurface
    val primary = MaterialTheme.colorScheme.primary
    val northLabel = stringResource(R.string.radar_direction_north)
    val eastLabel = stringResource(R.string.radar_direction_east)
    val southLabel = stringResource(R.string.radar_direction_south)
    val westLabel = stringResource(R.string.radar_direction_west)

    val gridColor = onSurface.copy(alpha = 0.14f)
    val pointerColor = primary.copy(alpha = 0.9f)
    val labelColor = onSurface.copy(alpha = 0.75f)

    val outerLineColor = primary.copy(alpha = 0.55f) // 外圈轮廓线（你也可以换成更偏绿）
    val innerLineColor = primary.copy(alpha = 0.40f) // 内圈轮廓线

    val outerFillColor = primary.copy(alpha = 0.10f) // 外圈轻填充
    val innerFillColor = primary.copy(alpha = 0.06f) // 内圈轻填充

    val targetGlow = onSurface.copy(alpha = 0.45f)

    val textPaint = remember(labelColor) {
        android.graphics.Paint().apply {
            isAntiAlias = true
            color = labelColor.toArgb()
            textAlign = android.graphics.Paint.Align.CENTER
        }
    }

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val cx = w / 2f
        val cy = h / 2f

        val outerR = min(w, h) * 0.42f        // 外圈最大半径（RSRP）
        val innerRMax = outerR * 0.55f        // 内圈最大半径（SINR）

        // 网格参考圆
        drawCircle(color = gridColor, radius = outerR, center = Offset(cx, cy), style = Stroke(width = 2f))
        drawCircle(color = gridColor, radius = outerR * 0.66f, center = Offset(cx, cy), style = Stroke(width = 2f))
        drawCircle(color = gridColor, radius = outerR * 0.33f, center = Offset(cx, cy), style = Stroke(width = 2f))

        val binSizeDeg = 30f

        // ===== 方向轴线 + 文字（N/E/S/W）=====
        run {
            val axisStroke = 2.5f
            val tickLen = (outerR * 0.10f).coerceIn(14f, 26f)     // 刻度长度（向外延伸）
            val labelPad = (outerR * 0.14f).coerceIn(18f, 34f)    // 文字离外圈的距离

            // 轴线：从中心到外圈（轻一点，不抢主图）
            fun axis(deg: Float) {
                val rad = Math.toRadians((deg - 90f).toDouble())
                val x = cx + (kotlin.math.cos(rad) * outerR).toFloat()
                val y = cy + (kotlin.math.sin(rad) * outerR).toFloat()

                // 在外圈末端再往外画一个短刻度
                val x2 = cx + (kotlin.math.cos(rad) * (outerR + tickLen)).toFloat()
                val y2 = cy + (kotlin.math.sin(rad) * (outerR + tickLen)).toFloat()
                drawLine(
                    color = gridColor.copy(alpha = 0.75f),
                    start = Offset(x, y),
                    end = Offset(x2, y2),
                    strokeWidth = axisStroke + 0.5f,
                    cap = StrokeCap.Round
                )
            }

            axis(0f)    // 北
            axis(90f)   // 东
            axis(180f)  // 南
            axis(270f)  // 西

            // 文本：圈外标注（你想中文就改成 "北/东/南/西"）
            textPaint.textSize = (min(w, h) * 0.040f).coerceIn(18f, 28f)

            fun drawDirLabel(text: String, deg: Float) {
                val rad = Math.toRadians((deg - 90f).toDouble())
                val x = cx + (kotlin.math.cos(rad) * (outerR + labelPad)).toFloat()
                val y = cy + (kotlin.math.sin(rad) * (outerR + labelPad)).toFloat()

                // 让字看起来“贴着方向”更自然：稍微按象限调一下对齐
                val wText = textPaint.measureText(text)
                val xAdj = when (deg.toInt()) {
                    90 -> 0f                 // 东：文字左对齐
                    270 -> -wText            // 西：文字右对齐
                    else -> -wText / 2f      // 北/南：居中
                }
                val yAdj = when (deg.toInt()) {
                    0 -> -2f                 // 北：稍微上提一点
                    180 -> textPaint.textSize // 南：稍微下沉一点
                    else -> textPaint.textSize * 0.35f
                }

                drawContext.canvas.nativeCanvas.drawText(
                    text,
                    x + xAdj,
                    y + yAdj,
                    textPaint
                )
            }

             drawDirLabel(northLabel, 0f)
             drawDirLabel(eastLabel, 90f)
             drawDirLabel(southLabel, 180f)
             drawDirLabel(westLabel, 270f)
        }


        // 1) 先算 12 个端点（外圈/内圈）
        val outerPts = Array(12) { Offset.Zero }
        val innerPts = Array(12) { Offset.Zero }

        for (i in 0 until 12) {
            val centerDeg = i * binSizeDeg // 正北开始：0/30/60...
            val rad = Math.toRadians((centerDeg - 90f).toDouble())

            val rsrp = binsRsrp[i]
            val sinr = binsSinr[i]

            val rsrpLen = outerR * rsrpToFrac(rsrp)
            val sinrLen = innerRMax * sinrToFrac(sinr)

            outerPts[i] = Offset(
                x = cx + (kotlin.math.cos(rad) * rsrpLen).toFloat(),
                y = cy + (kotlin.math.sin(rad) * rsrpLen).toFloat()
            )
            innerPts[i] = Offset(
                x = cx + (kotlin.math.cos(rad) * sinrLen).toFloat(),
                y = cy + (kotlin.math.sin(rad) * sinrLen).toFloat()
            )
        }

        // 2) 伞骨（RSRP 外圈骨 + SINR 内圈骨）
        for (i in 0 until 12) {
            val done = i in coveredBins
            val isTarget = i == targetBin

            // 外圈伞骨：颜色按 rsrp 档位
            val rsrp = binsRsrp[i]
            val outerColor = rsrpColor(rsrp).copy(alpha = if (done) 0.95f else 0.30f)

            drawLine(
                color = outerColor,
                start = Offset(cx, cy),
                end = outerPts[i],
                strokeWidth = if (done) 11f else 6f,
                cap = StrokeCap.Round
            )

            // 内圈伞骨：颜色按 sinr 档位
            val sinr = binsSinr[i]
            val innerColor = sinrColor(sinr).copy(alpha = if (done) 0.92f else 0.26f)

            drawLine(
                color = innerColor,
                start = Offset(cx, cy),
                end = innerPts[i],
                strokeWidth = if (done) 8f else 4.5f,
                cap = StrokeCap.Round
            )

            // 目标扇区轻微高亮（画一个短线段靠外，像“锁定目标”）
            if (isTarget) {
                val dir = (outerPts[i] - Offset(cx, cy))
                val len = kotlin.math.sqrt(dir.x * dir.x + dir.y * dir.y).coerceAtLeast(1f)
                val ux = dir.x / len
                val uy = dir.y / len
                val a = Offset(cx + ux * outerR * 0.82f, cy + uy * outerR * 0.82f)
                val b = Offset(cx + ux * outerR * 0.98f, cy + uy * outerR * 0.98f)

                drawLine(
                    color = targetGlow,
                    start = a,
                    end = b,
                    strokeWidth = 5f,
                    cap = StrokeCap.Round
                )
            }
        }

        // 3) 连接轮廓：外圈 RSRP 多边形 + 轻填充
        run {
            val p = androidx.compose.ui.graphics.Path()
            p.moveTo(outerPts[0].x, outerPts[0].y)
            for (i in 1 until 12) p.lineTo(outerPts[i].x, outerPts[i].y)
            p.close()

            // 轻填充（可注释掉）
            drawPath(path = p, color = outerFillColor)

            // 轮廓线
            drawPath(
                path = p,
                color = outerLineColor,
                style = Stroke(width = 4f, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)
            )
        }

        // 4) 连接轮廓：内圈 SINR 多边形 + 轻填充
        run {
            val p = androidx.compose.ui.graphics.Path()
            p.moveTo(innerPts[0].x, innerPts[0].y)
            for (i in 1 until 12) p.lineTo(innerPts[i].x, innerPts[i].y)
            p.close()

            drawPath(path = p, color = innerFillColor)

            drawPath(
                path = p,
                color = innerLineColor,
                style = Stroke(width = 3f, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)
            )
        }

        // 5) 标签：建议只在已完成扇区显示（你现在逻辑更干净）
        for (i in 0 until 12) {
            if (i !in coveredBins) continue

            val rsrp = binsRsrp[i]
            val sinr = binsSinr[i]
            val label = "${rsrp ?: "--"} / ${sinr?.toInt() ?: "--"}"

            // 放在内外中间
            val mid = Offset(
                x = (outerPts[i].x + innerPts[i].x) / 2f,
                y = (outerPts[i].y + innerPts[i].y) / 2f
            )

            textPaint.textSize = (min(w, h) * 0.034f).coerceIn(16f, 26f)
            drawContext.canvas.nativeCanvas.drawText(
                label,
                mid.x,
                mid.y + textPaint.textSize * 0.35f,
                textPaint
            )
        }

        // 6) 指针
        run {
            val rad = Math.toRadians((headingDeg - 90f).toDouble())
            val len = outerR * 0.98f
            val x2 = cx + (kotlin.math.cos(rad) * len).toFloat()
            val y2 = cy + (kotlin.math.sin(rad) * len).toFloat()

            drawLine(
                color = pointerColor,
                start = Offset(cx, cy),
                end = Offset(x2, y2),
                strokeWidth = 4f,
                cap = StrokeCap.Round
            )
        }

        // 中心点
        drawCircle(color = gridColor, radius = 6f, center = Offset(cx, cy))
    }
}


/** -140..-70 -> 0..1 */
private fun rsrpToFrac(rsrp: Int?): Float {
    if (rsrp == null) return 0f
    val min = -140f
    val max = -70f
    val v = rsrp.toFloat().coerceIn(min, max)
    return ((v - min) / (max - min)).coerceIn(0f, 1f)
}

/** -10..30 -> 0..1 */
private fun sinrToFrac(sinr: Float?): Float {
    if (sinr == null) return 0f
    val min = -10f
    val max = 30f
    val v = sinr.coerceIn(min, max)
    return ((v - min) / (max - min)).coerceIn(0f, 1f)
}

private fun rsrpColor(v: Int?): Color {
    if (v == null) return Color.Gray
    return when {
        v < -115 -> Color(0xFFC62828)
        v < -95  -> Color(0xFFEF6C00)
        v < -85  -> Color(0xFFF9A825)
        else     -> Color(0xFF2E7D32)
    }
}

private fun sinrColor(v: Float?): Color {
    if (v == null) return Color.Gray
    return when {
        v < 0f   -> Color(0xFFC62828)
        v < 10f  -> Color(0xFFF9A825)
        else     -> Color(0xFF2E7D32)
    }
}

/* ===== 解析 & 颜色（从你主页逻辑抽最小集） ===== */

private fun parseFirstNumber(s: String): Double? {
    val m = Regex("""-?\d+(\.\d+)?""").find(s) ?: return null
    return m.value.toDoubleOrNull()
}

private fun fractionFromDbm(text: String, min: Double, max: Double): Float {
    val v = parseFirstNumber(text) ?: return 0f
    return ((v - min) / (max - min)).toFloat().coerceIn(0f, 1f)
}

private fun fractionFromDb(text: String, min: Double, max: Double): Float {
    val v = parseFirstNumber(text) ?: return 0f
    return ((v - min) / (max - min)).toFloat().coerceIn(0f, 1f)
}

private fun qualityColor(f: Float): Color {
    val red = Color(0xFFFF3B30)
    val yellow = Color(0xFFFFCC00)
    val green = Color(0xFF34C759)
    return if (f < 0.5f) lerpColor(red, yellow, f / 0.5f) else lerpColor(yellow, green, (f - 0.5f) / 0.5f)
}

private fun lerpColor(a: Color, b: Color, t: Float): Color {
    val tt = t.coerceIn(0f, 1f)
    return Color(
        red = a.red + (b.red - a.red) * tt,
        green = a.green + (b.green - a.green) * tt,
        blue = a.blue + (b.blue - a.blue) * tt,
        alpha = a.alpha + (b.alpha - a.alpha) * tt
    )
}

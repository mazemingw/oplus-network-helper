package com.nvmex.networkhelper.ui.menu

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.telephony.SubscriptionManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.nvmex.networkhelper.R
import com.nvmex.networkhelper.model.network.NetworkPanelUiState
import com.nvmex.networkhelper.ui.base.toast
import com.nvmex.networkhelper.util.network.TelephonySnapshotterMultiSim
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IndoorDriveTestScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snapshotter = remember { TelephonySnapshotterMultiSim(context) }
    val points = remember { mutableStateListOf<IndoorPoint>() }
    var floorPlanUri by remember { mutableStateOf<Uri?>(null) }
    var floorPlanBitmap by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        floorPlanUri = uri
        floorPlanBitmap = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input)?.asImageBitmap()
            }
        }.getOrNull()
        points.clear()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.indoor_drive_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                }
            )
        }
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { imagePicker.launch("image/*") }) {
                    Icon(Icons.Default.Image, contentDescription = null)
                    Text(stringResource(R.string.indoor_drive_import_floorplan))
                }
                OutlinedButton(
                    enabled = points.isNotEmpty(),
                    onClick = { points.removeAt(points.lastIndex) }
                ) {
                    Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = null)
                    Text(stringResource(R.string.action_undo))
                }
                OutlinedButton(
                    enabled = points.isNotEmpty(),
                    onClick = {
                        val file = exportIndoorCsv(context, points)
                        shareCsv(context, file)
                    }
                ) {
                    Icon(Icons.Default.SaveAlt, contentDescription = null)
                    Text(stringResource(R.string.action_export_csv))
                }
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface)
                        .pointerInput(floorPlanBitmap, points.size) {
                            detectTapGestures { offset ->
                                if (floorPlanBitmap == null || size.width <= 0 || size.height <= 0) return@detectTapGestures
                                val x = (offset.x / size.width).coerceIn(0f, 1f)
                                val y = (offset.y / size.height).coerceIn(0f, 1f)
                                scope.launch {
                                    val (dataSubId, sims) = withContext(Dispatchers.IO) {
                                        SubscriptionManager.getDefaultDataSubscriptionId() to
                                                snapshotter.getActiveSims()
                                                    .sortedBy { it.simSlotIndex }
                                                    .take(2)
                                                    .map { info -> snapshotter.snapshotForSubId(info.subscriptionId) }
                                    }
                                    points += IndoorPoint(
                                        index = points.size + 1,
                                        ts = System.currentTimeMillis(),
                                        x = x,
                                        y = y,
                                        dataSubId = dataSubId,
                                        sim1 = sims.getOrNull(0),
                                        sim2 = sims.getOrNull(1)
                                    )
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    val img = floorPlanBitmap
                    if (img == null) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Default.FileUpload,
                                contentDescription = null,
                                modifier = Modifier.size(36.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(stringResource(R.string.indoor_drive_empty_hint))
                        }
                    } else {
                        Image(
                            bitmap = img,
                            contentDescription = stringResource(R.string.indoor_drive_floorplan),
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val mapped = points.map { Offset(it.x * size.width, it.y * size.height) }
                            for (i in 1 until mapped.size) {
                                drawLine(
                                    color = Color(0xFF448AFF),
                                    start = mapped[i - 1],
                                    end = mapped[i],
                                    strokeWidth = 4.dp.toPx(),
                                    cap = StrokeCap.Round
                                )
                            }
                            mapped.forEachIndexed { index, p ->
                                drawCircle(points[index].markerColor(), radius = 7.dp.toPx(), center = p)
                                drawCircle(Color.White, radius = 3.dp.toPx(), center = p)
                            }
                        }
                    }
                }
            }

            points.lastOrNull()?.let { p ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(stringResource(R.string.indoor_drive_point_title, p.index, formatIndoorTime(p.ts)), fontWeight = FontWeight.SemiBold)
                        Text("SIM1 ${p.sim1.summaryText()}", style = MaterialTheme.typography.bodySmall)
                        Text("SIM2 ${p.sim2.summaryText()}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

private data class IndoorPoint(
    val index: Int,
    val ts: Long,
    val x: Float,
    val y: Float,
    val dataSubId: Int,
    val sim1: NetworkPanelUiState?,
    val sim2: NetworkPanelUiState?
)

private fun IndoorPoint.markerColor(): Color {
    val dataState = when (dataSubId) {
        sim1?.subId -> sim1
        sim2?.subId -> sim2
        else -> sim1 ?: sim2
    }
    val rsrp = dataState?.let { parseSignalDb(it.rsrp) ?: parseSignalDb(it.ssRsrp) }
    return when {
        rsrp == null -> Color(0xFF9E9E9E)
        rsrp >= -85f -> Color(0xFF2E7D32)
        rsrp >= -95f -> Color(0xFF7CB342)
        rsrp >= -105f -> Color(0xFFFFB300)
        rsrp >= -115f -> Color(0xFFF4511E)
        else -> Color(0xFFD32F2F)
    }
}

private fun parseSignalDb(value: String): Float? {
    val normalized = value
        .replace('－', '-')
        .replace("dBm", "", ignoreCase = true)
        .replace("dB", "", ignoreCase = true)
        .trim()
    if (normalized.isBlank() || normalized == "-") return null
    return Regex("""-?\d+(\.\d+)?""").find(normalized)?.value?.toFloatOrNull()
}

private fun NetworkPanelUiState?.summaryText(): String {
    if (this == null) return "-"
    return "${cellType} TAC=$tac PCI=$pci CI=$ci ARFCN=$arfcn BAND=$band RSRP=${rsrp.takeIf { it != "-" } ?: ssRsrp}"
}

private fun exportIndoorCsv(context: Context, points: List<IndoorPoint>): File {
    val dir = File(context.cacheDir, "exports").apply { mkdirs() }
    val file = File(dir, "indoor_drive_${System.currentTimeMillis()}.csv")
    file.outputStream().writer(Charsets.UTF_8).use { out ->
        out.write("\uFEFF")
        out.appendLine(
            listOf(
                "index", "timestamp", "x", "y",
                "sim1_type", "sim1_tac", "sim1_pci", "sim1_ci", "sim1_arfcn", "sim1_band",
                "sim1_rsrp", "sim1_rsrq", "sim1_sinr", "sim1_ss_rsrp", "sim1_ss_rsrq", "sim1_ss_sinr",
                "sim2_type", "sim2_tac", "sim2_pci", "sim2_ci", "sim2_arfcn", "sim2_band",
                "sim2_rsrp", "sim2_rsrq", "sim2_sinr", "sim2_ss_rsrp", "sim2_ss_rsrq", "sim2_ss_sinr"
            ).joinToString(",")
        )
        points.forEach { p ->
            out.appendLine(
                listOf(
                    p.index.toString(),
                    p.ts.toString(),
                    p.x.toString(),
                    p.y.toString(),
                    p.sim1.csv("cellType"),
                    p.sim1.csv("tac"),
                    p.sim1.csv("pci"),
                    p.sim1.csv("ci"),
                    p.sim1.csv("arfcn"),
                    p.sim1.csv("band"),
                    p.sim1.csv("rsrp"),
                    p.sim1.csv("rsrq"),
                    p.sim1.csv("sinr"),
                    p.sim1.csv("ssRsrp"),
                    p.sim1.csv("ssRsrq"),
                    p.sim1.csv("ssSinr"),
                    p.sim2.csv("cellType"),
                    p.sim2.csv("tac"),
                    p.sim2.csv("pci"),
                    p.sim2.csv("ci"),
                    p.sim2.csv("arfcn"),
                    p.sim2.csv("band"),
                    p.sim2.csv("rsrp"),
                    p.sim2.csv("rsrq"),
                    p.sim2.csv("sinr"),
                    p.sim2.csv("ssRsrp"),
                    p.sim2.csv("ssRsrq"),
                    p.sim2.csv("ssSinr")
                ).joinToString(",") { csvEscape(it) }
            )
        }
    }
    toast(context, context.getString(R.string.toast_exported_file, file.name))
    return file
}

private fun NetworkPanelUiState?.csv(field: String): String {
    if (this == null) return ""
    return when (field) {
        "cellType" -> cellType
        "tac" -> tac
        "pci" -> pci
        "ci" -> ci
        "arfcn" -> arfcn
        "band" -> band
        "rsrp" -> if (cellType == "LTE") rsrp.blankDash() else ""
        "rsrq" -> if (cellType == "LTE") rsrq.blankDash() else ""
        "sinr" -> if (cellType == "LTE") sinr.blankDash() else ""
        "ssRsrp" -> if (cellType == "NR") ssRsrp.blankDash() else ""
        "ssRsrq" -> if (cellType == "NR") ssRsrq.blankDash() else ""
        "ssSinr" -> if (cellType == "NR") ssSinr.blankDash() else ""
        else -> ""
    }
}

private fun String.blankDash(): String = takeIf { it.isNotBlank() && it != "-" } ?: ""

private fun shareCsv(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/csv"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, context.getString(R.string.action_export_csv)))
}

private fun csvEscape(value: String): String {
    val needsQuote = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
    val escaped = value.replace("\"", "\"\"")
    return if (needsQuote) "\"$escaped\"" else escaped
}

private fun formatIndoorTime(ts: Long): String {
    return SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(ts))
}

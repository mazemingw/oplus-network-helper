package com.nvmex.networkhelper.ui.menu

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.nvmex.networkhelper.R

private const val UI_REFRESH_INTERVAL_MS = 450L
private const val RSSI_SMOOTH_OLD_WEIGHT = 0.7f
private const val RSSI_SMOOTH_NEW_WEIGHT = 0.3f

private data class BleDeviceItem(
    val address: String,
    val name: String?,
    val rssi: Int,
    val txPower: Int?,
    val connectable: Boolean?,
    val serviceUuids: List<String>,
    val firstSeen: Long,
    val lastSeen: Long
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BleScannerScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    val devices = remember { mutableStateMapOf<String, BleDeviceItem>() }
    val pendingResults = remember { mutableMapOf<String, ScanResult>() }

    var scanning by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("") }
    var permissionTick by remember { mutableStateOf(0) }
    var flushScheduled by remember { mutableStateOf(false) }

    var selectedDevice by remember { mutableStateOf<BleDeviceItem?>(null) }

    val adapter = remember {
        context.getSystemService(BluetoothManager::class.java)?.adapter
    }

    val permissions = remember {
        requiredBlePermissions()
    }

    val hasPermissions = remember(permissionTick) {
        permissions.all { permission ->
            ContextCompat.checkSelfPermission(
                context,
                permission
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        permissionTick++
    }

    fun flushPendingResults() {
        if (pendingResults.isEmpty()) return

        val snapshot = pendingResults.values.toList()
        pendingResults.clear()

        snapshot.forEach { result ->
            mergeScanResult(
                devices = devices,
                result = result
            )
        }
    }

    val flushRunnable = remember {
        object : Runnable {
            override fun run() {
                flushScheduled = false
                flushPendingResults()
            }
        }
    }

    fun scheduleFlush() {
        if (flushScheduled) return

        flushScheduled = true
        mainHandler.postDelayed(
            flushRunnable,
            UI_REFRESH_INTERVAL_MS
        )
    }

    val callback = remember {
        object : ScanCallback() {
            override fun onScanResult(
                callbackType: Int,
                result: ScanResult
            ) {
                mainHandler.post {
                    pendingResults[result.device.address] = result
                    scheduleFlush()
                }
            }

            override fun onBatchScanResults(
                results: MutableList<ScanResult>
            ) {
                mainHandler.post {
                    results.forEach { result ->
                        pendingResults[result.device.address] = result
                    }
                    scheduleFlush()
                }
            }

            override fun onScanFailed(errorCode: Int) {
                mainHandler.post {
                    scanning = false
                    statusText = context.getString(
                        R.string.ble_scan_failed,
                        errorCode
                    )
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        if (!scanning) return

        runCatching {
            adapter?.bluetoothLeScanner?.stopScan(callback)
        }

        scanning = false
        statusText = context.getString(R.string.ble_idle)

        mainHandler.removeCallbacks(flushRunnable)
        flushScheduled = false
        flushPendingResults()
    }

    @SuppressLint("MissingPermission")
    fun startScan() {
        if (!hasPermissions) {
            permissionLauncher.launch(permissions)
            return
        }

        if (adapter == null) {
            statusText = context.getString(R.string.ble_no_adapter)
            return
        }

        if (!adapter.isEnabled) {
            statusText = context.getString(R.string.ble_disabled)
            return
        }

        val scanner = adapter.bluetoothLeScanner

        if (scanner == null) {
            statusText = context.getString(R.string.ble_scanner_unavailable)
            return
        }

        runCatching {
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()

            scanner.startScan(
                null,
                settings,
                callback
            )

            scanning = true
            statusText = context.getString(R.string.ble_scanning)
        }.onFailure { e ->
            scanning = false
            statusText = e.message ?: context.getString(R.string.ble_scan_start_failed)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            stopScan()
            mainHandler.removeCallbacks(flushRunnable)
        }
    }

    val sortedDevices by remember {
        derivedStateOf {
            devices.values.sortedWith(
                compareByDescending<BleDeviceItem> { it.rssi }
                    .thenByDescending { it.lastSeen }
                    .thenBy { it.address }
            )
        }
    }

    selectedDevice?.let { device ->
        BleDeviceDetailDialog(
            item = device,
            onDismiss = {
                selectedDevice = null
            },
            onCopyMac = {
                copyTextToClipboard(
                    context = context,
                    label = "BLE MAC",
                    text = device.address
                )
                Toast.makeText(
                    context,
                    context.getString(R.string.ble_mac_copied),
                    Toast.LENGTH_SHORT
                ).show()
            },
            onCopyAll = {
                copyTextToClipboard(
                    context = context,
                    label = "BLE",
                    text = buildBleInfoText(device)
                )
                Toast.makeText(
                    context,
                    context.getString(R.string.ble_copied),
                    Toast.LENGTH_SHORT
                ).show()
            }
        )
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent
                ),
                title = {
                    Text(stringResource(R.string.ble_title))
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(
                horizontal = 18.dp,
                vertical = 12.dp
            ),
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            item {
                BleScannerControlPanel(
                    hasPermissions = hasPermissions,
                    scanning = scanning,
                    statusText = statusText,
                    deviceCount = sortedDevices.size,
                    onStartOrStopClick = {
                        if (scanning) {
                            stopScan()
                        } else {
                            startScan()
                        }
                    },
                    onClearClick = {
                        pendingResults.clear()
                        devices.clear()
                        selectedDevice = null
                    }
                )
            }

            if (sortedDevices.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.ble_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(
                            horizontal = 8.dp,
                            vertical = 18.dp
                        )
                    )
                }
            } else {
                items(
                    items = sortedDevices,
                    key = { it.address }
                ) { item ->
                    BleDeviceListItem(
                        item = item,
                        onClick = {
                            selectedDevice = item
                        },
                        onCopy = {
                            copyTextToClipboard(
                                context = context,
                                label = "BLE",
                                text = buildBleInfoText(item)
                            )
                            Toast.makeText(
                                context,
                                context.getString(R.string.ble_copied),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun BleScannerControlPanel(
    hasPermissions: Boolean,
    scanning: Boolean,
    statusText: String,
    deviceCount: Int,
    onStartOrStopClick: () -> Unit,
    onClearClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = if (hasPermissions) {
                    stringResource(R.string.ble_permission_ready)
                } else {
                    stringResource(R.string.ble_permission_required)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold
            )

            Text(
                text = statusText.ifBlank {
                    stringResource(R.string.ble_idle)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = onStartOrStopClick
                    ) {
                        Text(
                            text = if (scanning) {
                                stringResource(R.string.ble_stop_scan)
                            } else {
                                stringResource(R.string.ble_start_scan)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    OutlinedButton(
                        onClick = onClearClick
                    ) {
                        Text(
                            text = stringResource(R.string.ble_clear),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Text(
                    text = stringResource(R.string.ble_found_count, deviceCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BleDeviceListItem(
    item: BleDeviceItem,
    onClick: () -> Unit,
    onCopy: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onCopy
            )
            .padding(
                horizontal = 8.dp,
                vertical = 8.dp
            ),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = item.name?.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.ble_unknown_device),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Text(
                text = item.address,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.82f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            BleSignalBars(
                rssi = item.rssi,
                modifier = Modifier
                    .height(18.dp)
                    .width(32.dp)
            )

            Text(
                text = "${item.rssi} dBm",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun BleDeviceDetailDialog(
    item: BleDeviceItem,
    onDismiss: () -> Unit,
    onCopyMac: () -> Unit,
    onCopyAll: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = item.name?.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.ble_unknown_device),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.ble_signal),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        BleSignalBars(
                            rssi = item.rssi,
                            modifier = Modifier
                                .height(18.dp)
                                .width(32.dp)
                        )

                        Text(
                            text = "${item.rssi} dBm",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                HorizontalDivider()

                BleDetailRow(
                    label = stringResource(R.string.ble_mac),
                    value = item.address
                )

                BleDetailRow(
                    label = stringResource(R.string.ble_rssi),
                    value = "${item.rssi} dBm"
                )

                BleDetailRow(
                    label = stringResource(R.string.ble_signal_level),
                    value = "${rssiLevel(item.rssi)}/4"
                )

                BleDetailRow(
                    label = stringResource(R.string.ble_tx_power),
                    value = item.txPower?.let { "$it dBm" } ?: "-"
                )

                BleDetailRow(
                    label = stringResource(R.string.ble_connectable),
                    value = item.connectable?.let {
                        if (it) {
                            stringResource(R.string.state_yes)
                        } else {
                            stringResource(R.string.state_no)
                        }
                    } ?: "-"
                )

                BleDetailRow(
                    label = stringResource(R.string.ble_service_uuid),
                    value = if (item.serviceUuids.isNotEmpty()) {
                        item.serviceUuids.joinToString(separator = "\n")
                    } else {
                        "-"
                    }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onCopyMac
            ) {
                Text(stringResource(R.string.ble_copy_mac))
            }
        },
        dismissButton = {
            Row {
                TextButton(
                    onClick = onCopyAll
                ) {
                    Text(stringResource(R.string.ble_copy_all))
                }

                TextButton(
                    onClick = onDismiss
                ) {
                    Text(stringResource(R.string.action_close))
                }
            }
        }
    )
}

@Composable
private fun BleDetailRow(
    label: String,
    value: String
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun BleSignalBars(
    rssi: Int,
    modifier: Modifier = Modifier
) {
    val level = rssiLevel(rssi)

    val activeColor = when {
        rssi >= -70 -> MaterialTheme.colorScheme.primary
        rssi >= -85 -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.error
    }

    val inactiveColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(
        alpha = 0.28f
    )

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        for (index in 1..4) {
            val barHeight = when (index) {
                1 -> 5.dp
                2 -> 8.dp
                3 -> 12.dp
                else -> 16.dp
            }

            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(barHeight)
                    .clip(RoundedCornerShape(2.dp))
                    .background(
                        if (index <= level) {
                            activeColor
                        } else {
                            inactiveColor
                        }
                    )
            )
        }
    }
}

private fun rssiLevel(rssi: Int): Int {
    return when {
        rssi >= -60 -> 4
        rssi >= -70 -> 3
        rssi >= -85 -> 2
        rssi >= -95 -> 1
        else -> 0
    }
}

@SuppressLint("MissingPermission")
private fun ScanResult.toBleDeviceItem(): BleDeviceItem {
    val now = System.currentTimeMillis()

    val recordName = scanRecord?.deviceName

    val deviceName = runCatching {
        device.name
    }.getOrNull()

    return BleDeviceItem(
        address = device.address,
        name = recordName ?: deviceName,
        rssi = rssi,
        txPower = scanRecord?.txPowerLevel?.takeIf {
            it != Int.MIN_VALUE
        },
        connectable = isConnectable,
        serviceUuids = scanRecord?.serviceUuids?.map {
            it.uuid.toString()
        }.orEmpty(),
        firstSeen = now,
        lastSeen = now
    )
}

private fun mergeScanResult(
    devices: MutableMap<String, BleDeviceItem>,
    result: ScanResult
) {
    val newItem = result.toBleDeviceItem()
    val oldItem = devices[newItem.address]

    val smoothRssi = if (oldItem == null) {
        newItem.rssi
    } else {
        (
                oldItem.rssi * RSSI_SMOOTH_OLD_WEIGHT +
                        newItem.rssi * RSSI_SMOOTH_NEW_WEIGHT
                ).toInt()
    }

    devices[newItem.address] = newItem.copy(
        rssi = smoothRssi,
        firstSeen = oldItem?.firstSeen ?: newItem.firstSeen,
        lastSeen = newItem.lastSeen,
        name = newItem.name ?: oldItem?.name,
        serviceUuids = if (newItem.serviceUuids.isNotEmpty()) {
            newItem.serviceUuids
        } else {
            oldItem?.serviceUuids.orEmpty()
        },
        txPower = newItem.txPower ?: oldItem?.txPower,
        connectable = newItem.connectable ?: oldItem?.connectable
    )
}

private fun requiredBlePermissions(): Array<String> {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT
        )
    } else {
        arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    }
}

private fun buildBleInfoText(
    item: BleDeviceItem
): String {
    return buildString {
        appendLine("Name: ${item.name ?: "-"}")
        appendLine("MAC: ${item.address}")
        appendLine("Signal Level: ${rssiLevel(item.rssi)}/4")
        appendLine("RSSI: ${item.rssi} dBm")
        appendLine("TxPower: ${item.txPower?.let { "$it dBm" } ?: "-"}")
        appendLine("Connectable: ${item.connectable ?: "-"}")

        if (item.serviceUuids.isNotEmpty()) {
            appendLine("Services:")
            item.serviceUuids.forEach { uuid ->
                appendLine(uuid)
            }
        }
    }
}

private fun copyTextToClipboard(
    context: Context,
    label: String,
    text: String
) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)

    clipboard.setPrimaryClip(
        ClipData.newPlainText(
            label,
            text
        )
    )
}

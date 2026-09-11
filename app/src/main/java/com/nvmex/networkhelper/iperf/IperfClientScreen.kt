
package com.nvmex.networkhelper.iperf
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nvmex.networkhelper.R
import com.nvmex.networkhelper.iperf.client.GatewayInfo
import com.nvmex.networkhelper.iperf.client.IperfClientConfig
import com.nvmex.networkhelper.iperf.client.IperfClientViewModel
import com.nvmex.networkhelper.iperf.client.IperfPoint
import com.nvmex.networkhelper.iperf.client.IperfProtocol
import com.nvmex.networkhelper.iperf.client.SpeedGauge
import com.nvmex.networkhelper.iperf.client.observeGatewayInfo
import com.nvmex.networkhelper.util.windows.WindowUtils

@Composable
fun IperfClientScreen(vm: IperfClientViewModel = viewModel()) {
    val ui by vm.ui.collectAsState()
    val cfg = ui.config

    var showAdvanced by rememberSaveable { mutableStateOf(false) }

    val ctx = LocalContext.current
    val statusBarHeight = WindowUtils.getStatusBarHeight(ctx)

    fun set(block: (IperfClientConfig) -> IperfClientConfig) = vm.updateConfig(block)

    // =========================
    // 1) 文本态（防止“删不干净/读出来没显示/被重组覆盖”）
    // =========================
    var hostText by rememberSaveable { mutableStateOf(cfg.serverHost) }
    var hostEditing by remember { mutableStateOf(false) }
    LaunchedEffect(cfg.serverHost) { if (!hostEditing) hostText = cfg.serverHost }

    var portText by rememberSaveable { mutableStateOf(cfg.port.toString()) }
    var portEditing by remember { mutableStateOf(false) }
    LaunchedEffect(cfg.port) { if (!portEditing) portText = cfg.port.toString() }

    var durationText by rememberSaveable { mutableStateOf(cfg.durationSec.toString()) }
    var durationEditing by remember { mutableStateOf(false) }
    LaunchedEffect(cfg.durationSec) { if (!durationEditing) durationText = cfg.durationSec.toString() }

    var parallelText by rememberSaveable { mutableStateOf(cfg.parallel.toString()) }
    var parallelEditing by remember { mutableStateOf(false) }
    LaunchedEffect(cfg.parallel) { if (!parallelEditing) parallelText = cfg.parallel.toString() }

    var udpBandwidthText by rememberSaveable { mutableStateOf(cfg.udpBandwidth) }
    var udpBwEditing by remember { mutableStateOf(false) }
    LaunchedEffect(cfg.udpBandwidth) { if (!udpBwEditing) udpBandwidthText = cfg.udpBandwidth }

    var udpLenText by rememberSaveable { mutableStateOf(cfg.udpPacketLen?.toString() ?: "") }
    var udpLenEditing by remember { mutableStateOf(false) }
    LaunchedEffect(cfg.udpPacketLen) { if (!udpLenEditing) udpLenText = cfg.udpPacketLen?.toString() ?: "" }

    var omitText by rememberSaveable { mutableStateOf(cfg.omitSec.toString()) }
    var omitEditing by remember { mutableStateOf(false) }
    LaunchedEffect(cfg.omitSec) { if (!omitEditing) omitText = cfg.omitSec.toString() }

    var mssText by rememberSaveable { mutableStateOf(cfg.mss?.toString() ?: "") }
    var mssEditing by remember { mutableStateOf(false) }
    LaunchedEffect(cfg.mss) { if (!mssEditing) mssText = cfg.mss?.toString() ?: "" }

    var tosText by rememberSaveable { mutableStateOf(cfg.tos?.toString() ?: "") }
    var tosEditing by remember { mutableStateOf(false) }
    LaunchedEffect(cfg.tos) { if (!tosEditing) tosText = cfg.tos?.toString() ?: "" }

    var bindText by rememberSaveable { mutableStateOf(cfg.bindAddress ?: "") }
    var bindEditing by remember { mutableStateOf(false) }
    LaunchedEffect(cfg.bindAddress) { if (!bindEditing) bindText = cfg.bindAddress ?: "" }

    var extraArgsText by rememberSaveable { mutableStateOf(cfg.extraArgs.joinToString(" ")) }
    var extraEditing by remember { mutableStateOf(false) }
    LaunchedEffect(cfg.extraArgs) { if (!extraEditing) extraArgsText = cfg.extraArgs.joinToString(" ") }

    //取得网关状态（AUTO REFRESH）
    val gatewayInfo by produceState(initialValue = GatewayInfo(false, null, null), ctx) {
        observeGatewayInfo(ctx).collect { value = it }
    }
    val gateway = gatewayInfo.best

    // =========================
    // 2) commit：把文本态解析/规范化写回 cfg（触发 DataStore 保存）
    // =========================
    fun commitHost() {
        val v = hostText.trim()
        if (v != cfg.serverHost) set { it.copy(serverHost = v) }
        hostText = v
    }

    fun commitPort() {
        val p = portText.toIntOrNull()?.coerceIn(1, 65535) ?: 5201
        if (p != cfg.port) set { it.copy(port = p) }
        portText = p.toString()
    }

    fun commitDuration() {
        val t = durationText.toIntOrNull()?.coerceIn(1, 999) ?: 10
        if (t != cfg.durationSec) set { it.copy(durationSec = t) }
        durationText = t.toString()
    }

    fun commitParallel() {
        val p = parallelText.toIntOrNull()?.coerceIn(1, 32) ?: 1
        if (p != cfg.parallel) set { it.copy(parallel = p) }
        parallelText = p.toString()
    }

    fun commitUdpBandwidth() {
        val v = udpBandwidthText.trim()
        if (v != cfg.udpBandwidth) set { it.copy(udpBandwidth = v) }
        udpBandwidthText = v
    }

    fun commitUdpLen() {
        val n = udpLenText.toIntOrNull()
        if (n != cfg.udpPacketLen) set { it.copy(udpPacketLen = n) }
        udpLenText = n?.toString() ?: ""
    }

    fun commitOmit() {
        val n = omitText.toIntOrNull()?.coerceIn(0, 30) ?: 0
        if (n != cfg.omitSec) set { it.copy(omitSec = n) }
        omitText = n.toString()
    }

    fun commitMss() {
        val n = mssText.toIntOrNull()
        if (n != cfg.mss) set { it.copy(mss = n) }
        mssText = n?.toString() ?: ""
    }

    fun commitTos() {
        val n = tosText.toIntOrNull()?.coerceIn(0, 255)
        if (n != cfg.tos) set { it.copy(tos = n) }
        tosText = n?.toString() ?: ""
    }

    fun commitBind() {
        val v = bindText.trim().ifBlank { null }
        if (v != cfg.bindAddress) set { it.copy(bindAddress = v) }
        bindText = v ?: ""
    }

    fun commitExtraArgs() {
        val tokens = extraArgsText.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens != cfg.extraArgs) set { it.copy(extraArgs = tokens) }
        extraArgsText = tokens.joinToString(" ")
    }

    fun commitAllBeforeStart() {
        commitHost()
        commitPort()
        commitDuration()
        commitParallel()
        commitOmit()
        commitMss()
        commitTos()
        commitBind()
        commitExtraArgs()
        if (cfg.protocol == IperfProtocol.UDP) {
            commitUdpBandwidth()
            commitUdpLen()
        }
    }

    // =========================
    // UI
    // =========================
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .padding(top = statusBarHeight),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {

        // Host + Port
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = hostText,
                    onValueChange = { hostText = it },
                    label = { Text(stringResource(R.string.iperf_server_host_label)) },
                    singleLine = true,
                    modifier = Modifier
                        .weight(3f)
                        .onFocusChanged { fs ->
                            hostEditing = fs.isFocused
                            if (!fs.isFocused) commitHost()
                        },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { commitHost() })
                )

                OutlinedTextField(
                    value = portText,
                    onValueChange = { v -> portText = v.filter { it.isDigit() }.take(5) },
                    label = { Text(stringResource(R.string.iperf_port)) },
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .onFocusChanged { fs ->
                            portEditing = fs.isFocused
                            if (!fs.isFocused) commitPort()
                        },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { commitPort() })
                )
            }
        }

        //gateway
        item {
            val text = when {
                gatewayInfo.best == null -> stringResource(R.string.iperf_gateway_missing)
                !gatewayInfo.isWifi -> stringResource(R.string.iperf_gateway_not_wifi, gateway ?: "-")
                else -> stringResource(R.string.iperf_gateway, gateway ?: "-")
            }

            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = if (gatewayInfo.best == null) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.primary
            )
        }


        item {
            OutlinedButton(
                onClick = {
                    val g = gatewayInfo.best
                    if (g != null && gatewayInfo.isWifi) {
                        hostText = g
                        commitHost() // 立即写回 cfg + DataStore
                    }
                },
                enabled = gatewayInfo.best != null && gatewayInfo.isWifi,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.iperf_use_gateway))
            }
        }


        // Protocol
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilterChip(
                    selected = cfg.protocol == IperfProtocol.TCP,
                    onClick = { if (cfg.protocol != IperfProtocol.TCP) set { it.copy(protocol = IperfProtocol.TCP) } },
                    label = { Text("TCP") }
                )
                FilterChip(
                    selected = cfg.protocol == IperfProtocol.UDP,
                    onClick = { if (cfg.protocol != IperfProtocol.UDP) set { it.copy(protocol = IperfProtocol.UDP) } },
                    label = { Text("UDP") }
                )
            }
        }

        // Duration + Parallel
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = durationText,
                    onValueChange = { v -> durationText = v.filter { it.isDigit() }.take(3) },
                    label = { Text(stringResource(R.string.iperf_duration)) },
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .onFocusChanged { fs ->
                            durationEditing = fs.isFocused
                            if (!fs.isFocused) commitDuration()
                        },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { commitDuration() })
                )

                OutlinedTextField(
                    value = parallelText,
                    onValueChange = { v -> parallelText = v.filter { it.isDigit() }.take(2) },
                    label = { Text(stringResource(R.string.iperf_parallel)) },
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .onFocusChanged { fs ->
                            parallelEditing = fs.isFocused
                            if (!fs.isFocused) commitParallel()
                        },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { commitParallel() })
                )
            }
        }

        // Reverse (修正：用 checked 入参)
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = cfg.reverse,
                    onCheckedChange = { checked ->
                        if (checked != cfg.reverse) set { it.copy(reverse = checked) }
                    }
                )
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.iperf_reverse))
            }
        }

        // UDP
        if (cfg.protocol == IperfProtocol.UDP) {
            item {
                OutlinedTextField(
                    value = udpBandwidthText,
                    onValueChange = { udpBandwidthText = it },
                    label = { Text(stringResource(R.string.iperf_udp_bandwidth)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { fs ->
                            udpBwEditing = fs.isFocused
                            if (!fs.isFocused) commitUdpBandwidth()
                        },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { commitUdpBandwidth() })
                )
            }

            item {
                OutlinedTextField(
                    value = udpLenText,
                    onValueChange = { v -> udpLenText = v.filter { it.isDigit() }.take(5) },
                    label = { Text(stringResource(R.string.iperf_udp_length)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { fs ->
                            udpLenEditing = fs.isFocused
                            if (!fs.isFocused) commitUdpLen()
                        },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { commitUdpLen() })
                )
            }
        }

        // Advanced Header
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.iperf_advanced), style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = { showAdvanced = !showAdvanced }) {
                    Text(
                        if (showAdvanced) {
                            stringResource(R.string.action_collapse)
                        } else {
                            stringResource(R.string.action_expand)
                        }
                    )
                }
            }
        }

        if (showAdvanced) {
            item {
                OutlinedTextField(
                    value = omitText,
                    onValueChange = { v -> omitText = v.filter { it.isDigit() }.take(3) },
                    label = { Text(stringResource(R.string.iperf_omit)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { fs ->
                            omitEditing = fs.isFocused
                            if (!fs.isFocused) commitOmit()
                        },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { commitOmit() })
                )
            }

            item {
                OutlinedTextField(
                    value = mssText,
                    onValueChange = { v -> mssText = v.filter { it.isDigit() }.take(5) },
                    label = { Text(stringResource(R.string.iperf_mss)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { fs ->
                            mssEditing = fs.isFocused
                            if (!fs.isFocused) commitMss()
                        },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { commitMss() })
                )
            }

            item {
                OutlinedTextField(
                    value = tosText,
                    onValueChange = { v -> tosText = v.filter { it.isDigit() }.take(3) },
                    label = { Text(stringResource(R.string.iperf_tos_dscp)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { fs ->
                            tosEditing = fs.isFocused
                            if (!fs.isFocused) commitTos()
                        },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { commitTos() })
                )
            }

            item {
                OutlinedTextField(
                    value = bindText,
                    onValueChange = { bindText = it },
                    label = { Text(stringResource(R.string.iperf_bind_address)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { fs ->
                            bindEditing = fs.isFocused
                            if (!fs.isFocused) commitBind()
                        },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { commitBind() })
                )
            }

            item {
                OutlinedTextField(
                    value = extraArgsText,
                    onValueChange = { extraArgsText = it },
                    label = { Text(stringResource(R.string.iperf_extra_args)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { fs ->
                            extraEditing = fs.isFocused
                            if (!fs.isFocused) commitExtraArgs()
                        },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { commitExtraArgs() })
                )
            }
        }

        // Start/Stop
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = {
                        commitAllBeforeStart()
                        vm.start()
                    },
                    enabled = !ui.running,
                    modifier = Modifier.weight(1f)
                ) { Text(stringResource(R.string.iperf_start)) }

                OutlinedButton(
                    onClick = { vm.stop() },
                    enabled = ui.running,
                    modifier = Modifier.weight(1f)
                ) { Text(stringResource(R.string.iperf_stop)) }
            }
        }

        // ===== Gauge：放在开始/停止按钮下面最合适（主视觉）=====
        item {
            val smoothMbps = rememberEmaMbps(ui.points)
            val peak = remember(ui.points) {
                (ui.points.maxOfOrNull { it.mbps } ?: 100.0).toFloat()
            }
            val max = (peak * 1.2f).coerceIn(100f, 5000f) // 给个上下限，避免太离谱

            SpeedGauge(
                currentMbps = if (ui.running) smoothMbps else 0f,
                maxMbps = 2000f,
                points = ui.points,
                modifier = Modifier.fillMaxWidth()
            )
        }



        // Summary
        ui.summary?.let { s ->
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(stringResource(R.string.iperf_result), style = MaterialTheme.typography.titleMedium)
                        if (s.error != null) {
                            Text(stringResource(R.string.iperf_error, s.error), color = MaterialTheme.colorScheme.error)
                        } else {
                            Text(stringResource(R.string.iperf_summary, s.protocol, s.seconds, s.parallel, s.reverse.toString()))
                            s.senderMbps?.let { Text(stringResource(R.string.iperf_throughput, it)) }
                            s.receiverMbps?.let { Text(stringResource(R.string.iperf_receive, it)) }
                        }
                    }
                }
            }
        }

        item { Divider() }




        // Log
        item {
            Text(stringResource(R.string.iperf_log), style = MaterialTheme.typography.titleSmall)

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 360.dp, max = 460.dp)
                    .padding(top = 6.dp)
            ) {
                val scroll = rememberScrollState()
                SelectionContainer {
                    Text(
                        ui.log,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scroll)
                    )
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
fun rememberEmaMbps(points: List<IperfPoint>, alpha: Float = 0.22f): Float {
    val latest = points.lastOrNull()?.mbps?.toFloat() ?: 0f
    var ema by remember { mutableStateOf(latest) }

    LaunchedEffect(latest) {
        ema = alpha * latest + (1f - alpha) * ema
    }
    return ema
}


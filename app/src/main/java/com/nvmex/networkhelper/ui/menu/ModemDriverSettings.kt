package com.nvmex.networkhelper.ui.menu

import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.nvmex.networkhelper.R
import com.nvmex.networkhelper.util.modem.ModemDriver
import com.nvmex.networkhelper.viewmodel.menu.ModemDriversViewModel
import dev.ririxi.bunnylock.modem.RootState

private val driverShape = RoundedCornerShape(4.dp)

@Composable
internal fun DriverCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun DriverSourceIcon(bunny: Boolean) {
    Image(painterResource(if (bunny) R.drawable.driver_bunnylock else R.drawable.driver_qcom_bandmenu),
        if (bunny) "bunnylock" else "qcom-band-menu", Modifier.size(32.dp)
            .background(if (bunny) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface, driverShape))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ModemDriverSettings(vm: ModemDriversViewModel, enabled: Boolean,
                                 releaseLegacy: suspend () -> Unit, nativeReady: suspend () -> Unit) {
    val selected by vm.selected.collectAsState()
    val state by vm.state.collectAsState()
    val recovery by vm.systemRecovery.collectAsState()
    val uri = LocalUriHandler.current
    val clipboard = LocalClipboardManager.current
    var expanded by remember { mutableStateOf(false) }
    var command by rememberSaveable { mutableStateOf("AT") }
    var node by rememberSaveable { mutableStateOf("") }
    var reply by rememberSaveable { mutableStateOf("") }
    var confirm by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state.atNode) { if (state.atNode.isNotBlank()) node = state.atNode }

    DriverCard("后端驱动") {
        Box {
            OutlinedButton(onClick = { expanded = true }, enabled = enabled, shape = driverShape, modifier = Modifier.fillMaxWidth()) {
                DriverSourceIcon(selected.isBunny)
                Spacer(Modifier.width(10.dp))
                Text(selected.title, Modifier.weight(1f))
                Text("▾")
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                ModemDriver.entries.forEach { driver ->
                    DropdownMenuItem(text = { Text(driver.title) }, leadingIcon = { DriverSourceIcon(driver.isBunny) },
                        onClick = { expanded = false; vm.select(driver, releaseLegacy, nativeReady) })
                }
            }
        }
        Text("权限：${state.access?.label ?: "尚未连接"} · 当前：${if (selected.isBunny) state.backend?.name ?: "等待识别" else "原有高通 QMI"}",
            style = MaterialTheme.typography.bodySmall)
        if (state.root is RootState.Denied && selected.isBunny) Text((state.root as RootState.Denied).reason, style = MaterialTheme.typography.bodySmall)
        if (state.root == RootState.Checking && selected.isBunny) Text(state.connectStep.ifBlank { "连接后端…" }, style = MaterialTheme.typography.bodySmall)
        if (selected.isBunny) {
            Text("无 Root 时需启动并授权 Shizuku；可用功能取决于设备接口。", style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("持久保存锁定", Modifier.weight(1f))
                Switch(checked = state.persist, onCheckedChange = vm::persist, enabled = enabled && state.root == RootState.Granted)
            }
        }
    }

    DriverCard("射频与通道") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { confirm = "radio" }, enabled = enabled, shape = driverShape) { Text("重启移动网络") }
            OutlinedButton(onClick = { confirm = "modem" }, enabled = enabled, shape = driverShape) { Text("重启基带") }
            OutlinedButton(onClick = { vm.clearChannels(releaseLegacy, nativeReady) }, enabled = enabled, shape = driverShape) { Text("清理通道") }
            OutlinedButton(onClick = vm::showSystemComponents, enabled = enabled, shape = driverShape) { Text("系统组件") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("替我决定")
                Text("清理失败时预选无线扩展组件，经确认逐个暂停并验证。", style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = recovery.decideForMe, onCheckedChange = vm::decideForMe, enabled = enabled)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("自动恢复")
                Text("退出应用时恢复助手暂停前正在运行的组件；切到后台不恢复。", style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = recovery.autoRestore, onCheckedChange = vm::autoRestore, enabled = enabled)
        }
        val paused = recovery.services.count { it.pausedByUs }
        if (paused > 0) Text("助手暂停了 $paused 个系统组件${if (recovery.autoRestore) "，退出时自动恢复" else "，请按需手动恢复"}。",
            style = MaterialTheme.typography.bodySmall)
    }

    DriverCard("AT 控制台") {
        OutlinedTextField(value = node, onValueChange = { node = it }, label = { Text("AT 节点 / RIL（如 ril:1）") },
            singleLine = true, enabled = enabled, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = command, onValueChange = { command = it }, label = { Text("AT 命令") },
            singleLine = true, enabled = enabled, modifier = Modifier.fillMaxWidth())
        Button(onClick = { vm.serviceAction(releaseLegacy, nativeReady) {
            if (node.isNotBlank()) setAtNode(node)
            reply = sendAt(command)
            log("AT > $command\n$reply")
            "AT 命令已执行"
        } }, enabled = enabled && command.isNotBlank(), shape = driverShape) { Text("发送") }
        if (reply.isNotBlank()) SelectionContainer { Text(reply, style = MaterialTheme.typography.bodySmall) }
    }

    DriverCard("设备") {
        val device = state.device
        Text("${device?.manufacturer ?: Build.MANUFACTURER} · ${device?.model ?: Build.MODEL}")
        Text("Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}", style = MaterialTheme.typography.bodySmall)
        if (device != null) {
            Text("SoC：${device.socModel} · ${device.platform}\n基带：${device.baseband}\nRIL：${device.ril}", style = MaterialTheme.typography.bodySmall)
            Text("QRTR：${device.qrtr} · MTK RIL：${device.mtkOemHook} · Pixel：${device.pixelRouter} · HAL：${device.aospChannels}", style = MaterialTheme.typography.bodySmall)
        }
        OutlinedButton(onClick = { vm.serviceAction(releaseLegacy, nativeReady) { "设备信息已更新" } }, enabled = enabled, shape = driverShape) { Text("读取设备") }
    }

    DriverCard("日志") {
        OutlinedButton(onClick = { clipboard.setText(AnnotatedString(vm.diagnostics())) }, shape = driverShape) { Text("复制诊断") }
        SelectionContainer {
            Text(state.log.takeLast(80).joinToString("\n").ifBlank { "暂无后端日志" }, style = MaterialTheme.typography.bodySmall)
        }
    }
    DriverCard("感谢开源贡献") {
        Text("诚挚感谢 qcom-band-menu 与 bunnylock 的作者和贡献者。他们开放的协议实现与后端能力，让欧加网络助手得以继续完善。",
            style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { uri.openUri("https://github.com/Fronsipswu/qcom-band-menu") }) {
            DriverSourceIcon(false); Spacer(Modifier.width(8.dp)); Text("qcom-band-menu · GitHub")
        }
        TextButton(onClick = { uri.openUri("https://github.com/btsearch/bunnylock") }) {
            DriverSourceIcon(true); Spacer(Modifier.width(8.dp)); Text("bunnylock · GitHub")
        }
        Text("bunnylock 后端：GPL-3.0 · 源码与许可证随项目提供。", style = MaterialTheme.typography.bodySmall)
    }

    confirm?.let { operation ->
        AlertDialog(onDismissRequest = { confirm = null }, title = { Text(if (operation == "radio") "重启移动网络？" else "重启基带？") },
            text = { Text("此操作会暂时中断移动网络连接。") },
            confirmButton = { TextButton(onClick = {
                confirm = null
                vm.serviceAction(releaseLegacy, nativeReady) { if (operation == "radio") radioCycle() else rebootModem() }
            }) { Text("执行") } }, dismissButton = { TextButton(onClick = { confirm = null }) { Text("取消") } })
    }
}

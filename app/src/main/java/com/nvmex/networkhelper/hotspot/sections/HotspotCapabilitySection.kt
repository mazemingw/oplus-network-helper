package com.nvmex.networkhelper.hotspot.sections

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nvmex.networkhelper.R
import com.nvmex.networkhelper.hotspot.ChannelOption
import com.nvmex.networkhelper.hotspot.utils.WifiCountryCodeUseCase
import com.nvmex.networkhelper.util.shell.SuShellRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/* ===========================
 * 5) Capability Section
 * =========================== */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HotspotCapabilitySection(
    capCountryCode: String?,
    capMaxClients: Int?,
    capBand24: Boolean?,
    capBand5: Boolean?,
    capBand6: Boolean?,
    capBand60: Boolean?,
    capChannels2g: List<ChannelOption>,
    capChannels5g: List<ChannelOption>,
    capChannels6g: List<ChannelOption>,
    capChannels60g: List<ChannelOption>,
    showChannelsDetail: Boolean,
    onToggleChannelsDetail: () -> Unit,
    suRunner: SuShellRunner, // ✅新增：给它 root 执行器
) {
    val useCase = remember(suRunner) { WifiCountryCodeUseCase(suRunner) }
    val restoreDone = stringResource(R.string.hotspot_restore_done)
    val restoreMismatch = stringResource(R.string.hotspot_restore_mismatch)
    val restoreUnverified = stringResource(R.string.hotspot_restore_unverified)
    val restoreFailed = stringResource(R.string.hotspot_restore_failed)
    val switchedDone = stringResource(R.string.hotspot_switched_done)
    val switchMismatch = stringResource(R.string.hotspot_switch_mismatch)
    val switchUnverified = stringResource(R.string.hotspot_switch_unverified)
    val switchFailed = stringResource(R.string.hotspot_switch_failed)

    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var showPicker by remember { mutableStateOf(false) }
    var applying by remember { mutableStateOf(false) }

    // 这两个是你要的“记原本”和“当前展示”
    var originalCountry by remember { mutableStateOf<String?>(null) }
    var currentCountry by remember { mutableStateOf<String?>(capCountryCode) }

    // 首次进来：尝试读一次国家码，作为 original 快照（只写一次）
    LaunchedEffect(Unit) {
        if (originalCountry == null) {
            val read = withContext(Dispatchers.IO) { useCase.readCountryCode() }
            if (!read.isNullOrBlank()) {
                originalCountry = read
                currentCountry = read
            } else {
                // 读不到也别强求，至少 UI 还能显示 capCountryCode
                currentCountry = capCountryCode
            }
        }
    }

    // 顶部标题
    Text(stringResource(R.string.hotspot_capability_title), style = MaterialTheme.typography.titleMedium)

    // ✅ 改造后的“驱动国家 + 切换/复原”
    DriverCountryRow(
        capCountryCode = currentCountry ?: capCountryCode,
        originalCountryCode = originalCountry,
        applying = applying,
        onClickSwitch = { showPicker = true },
        onClickRestore = {
            val target = originalCountry
            if (target.isNullOrBlank()) return@DriverCountryRow
            scope.launch {
                applying = true
                val res = withContext(Dispatchers.IO) { useCase.forceCountryCode(target) }
                val verify = withContext(Dispatchers.IO) { useCase.readCountryCode() }
                applying = false

                if (res.code == 0) {
                    // 校验：能读到就严格比对；读不到就提示“已执行但无法确认”
                    if (verify != null) {
                        currentCountry = verify
                        if (verify.equals(target, ignoreCase = true)) {
                            snackbar.showSnackbar(restoreDone.format(verify))
                        } else {
                            snackbar.showSnackbar(restoreMismatch.format(verify))
                        }
                    } else {
                        snackbar.showSnackbar(restoreUnverified)
                    }
                } else {
                    snackbar.showSnackbar(restoreFailed.format(res.err.ifBlank { res.out }.ifBlank { "code=${res.code}" }))
                }
            }
        }
    )

    Text(stringResource(R.string.hotspot_max_clients, capMaxClients?.toString() ?: "-"), style = MaterialTheme.typography.bodySmall)

    // ===== 频段支持 =====
    Text(stringResource(R.string.hotspot_band_support), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        CapabilityTag(label = "2.4G", supported = capBand24)
        CapabilityTag(label = "5G", supported = capBand5)
        CapabilityTag(label = "6G", supported = capBand6)
        CapabilityTag(label = "60G", supported = capBand60)
    }

    // ===== 信道数量 =====
    Text(stringResource(R.string.hotspot_channel_count), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        CountTag(label = "2G", count = capChannels2g.count { it.channel != 0 })
        CountTag(label = "5G", count = capChannels5g.count { it.channel != 0 })
        CountTag(label = "6G", count = capChannels6g.count { it.channel != 0 })
        CountTag(label = "60G", count = capChannels60g.count { it.channel != 0 })
    }

    OutlinedButton(
        onClick = onToggleChannelsDetail,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            if (showChannelsDetail) {
                stringResource(R.string.hotspot_collapse_channels)
            } else {
                stringResource(R.string.hotspot_expand_channels)
            }
        )
    }

    if (showChannelsDetail) {
        ChannelGridBlock("2.4G", capChannels2g)
        ChannelGridBlock("5G", capChannels5g)
        ChannelGridBlock("6G", capChannels6g)
        ChannelGridBlock("60G", capChannels60g)
    }

    // Snackbar 宿主（你也可以放到更外层 Scaffold 里）
    androidx.compose.material3.SnackbarHost(hostState = snackbar)

    if (showPicker) {
        CountryPickerDialog(
            current = currentCountry ?: capCountryCode,
            onDismiss = { showPicker = false },
            onPick = { picked ->
                showPicker = false
                // 切换逻辑
                scope.launch {
                    // 第一次切换前，如果 originalCountry 还没拿到，尝试补一次
                    if (originalCountry.isNullOrBlank()) {
                        val read = withContext(Dispatchers.IO) { useCase.readCountryCode() }
                        if (!read.isNullOrBlank()) originalCountry = read
                    }

                    applying = true
                    val res = withContext(Dispatchers.IO) { useCase.forceCountryCode(picked) }
                    val verify = withContext(Dispatchers.IO) { useCase.readCountryCode() }
                    applying = false

                    if (res.code == 0) {
                        if (verify != null) {
                            currentCountry = verify
                            if (verify.equals(picked, ignoreCase = true)) {
                                snackbar.showSnackbar(switchedDone.format(verify))
                            } else {
                                snackbar.showSnackbar(switchMismatch.format(verify))
                            }
                        } else {
                            currentCountry = picked // 视觉上先跟随用户选择
                            snackbar.showSnackbar(switchUnverified.format(picked))
                        }
                    } else {
                        snackbar.showSnackbar(switchFailed.format(res.err.ifBlank { res.out }.ifBlank { "code=${res.code}" }))
                    }
                }
            }
        )
    }
}


/////////////////////////////////用以规范化信道列表////////////////////////////////////
@Composable
private fun ChannelGridBlock(
    title: String,
    channels: List<ChannelOption>,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // 标题 + 统计
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.hotspot_supported_channels, channels.count { it.channel != 0 }),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (channels.isEmpty()) {
            Text("-", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return
        }

        // Auto(0) 一般没必要放进网格里占位置（你想保留也可以）
        val items = remember(channels) { channels.filter { it.channel != 0 } }

        // 简单自适应：条目较短 -> 3列；内容偏长（5G/DFS带频率） -> 2列更稳
        val use3Cols = remember(items) {
            // 粗略判断：display 平均长度较短就用 3 列
            val avgLen = if (items.isNotEmpty()) items.sumOf { it.display.length } / items.size else 0
            avgLen <= 14
        }
        val cols = if (use3Cols) 3 else 2

        Surface(
            tonalElevation = 1.dp,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth()
        ) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(cols),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 10_000.dp) // 给一个极大但有限的上界
                    .padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                userScrollEnabled = false // ⭐ 关键：禁用 Grid 自滚动
            )
            {
                items(items, key = { it.channel }) { opt ->
                    ChannelCell(opt)
                }
            }
        }
    }
}

@Composable
private fun ChannelCell(opt: ChannelOption) {
    val isDfs = opt.isDfs
    val bg = if (isDfs) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface
    val textColor = if (isDfs) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface

    Surface(
        color = bg,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
        tonalElevation = 0.dp
    ) {
        Text(
            text = opt.display,
            style = MaterialTheme.typography.bodySmall,
            color = textColor,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
        )
    }
}
/////////////////////////////////用以规范化信道列表////////////////////////////////////

/////////////////////////////////用以规范化频段支持////////////////////////////////////
@Composable
private fun CapabilityTag(
    label: String,
    supported: Boolean?
) {
    // true=绿，false=红，null=灰
    val (bg, fg, text) = when (supported) {
        true -> Triple(
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer,
            stringResource(R.string.hotspot_tag_supported, label)
        )
        false -> Triple(
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
            stringResource(R.string.hotspot_tag_unsupported, label)
        )
        null -> Triple(
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
            stringResource(R.string.hotspot_tag_unknown, label)
        )
    }

    Surface(
        color = bg,
        contentColor = fg,
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            maxLines = 1
        )
    }
}

@Composable
private fun CountTag(
    label: String,
    count: Int
) {
    // 统计标签不需要红绿，保持中性但显眼
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            text = "$label：$count",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            maxLines = 1
        )
    }
}
/////////////////////////////////用以规范化频段支持////////////////////////////////////

/////////////////////////////////用以切换国家////////////////////////////////////
@Composable
private fun DriverCountryRow(
    capCountryCode: String?,
    originalCountryCode: String?,
    applying: Boolean,
    onClickSwitch: () -> Unit,
    onClickRestore: () -> Unit
) {
    val linkColor = MaterialTheme.colorScheme.primary
    val disabledColor = MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 主文案
        Text(
            text = stringResource(R.string.hotspot_driver_country, capCountryCode ?: "-"),
            style = MaterialTheme.typography.bodySmall
        )

        Spacer(Modifier.width(8.dp))

        // 「切换」
        Text(
            text = if (applying) stringResource(R.string.hotspot_switching) else stringResource(R.string.hotspot_switch),
            style = MaterialTheme.typography.bodySmall,
            color = if (applying) disabledColor else linkColor,
            modifier = Modifier.then(
                if (!applying) Modifier.clickable(onClick = onClickSwitch)
                else Modifier
            )
        )

        Spacer(Modifier.width(8.dp))

        // 「复原」
        val canRestore = !originalCountryCode.isNullOrBlank() && !applying
        Text(
            text = stringResource(R.string.hotspot_restore),
            style = MaterialTheme.typography.bodySmall,
            color = if (canRestore) linkColor else disabledColor,
            modifier = Modifier.then(
                if (canRestore) Modifier.clickable(onClick = onClickRestore)
                else Modifier
            )
        )
    }
}


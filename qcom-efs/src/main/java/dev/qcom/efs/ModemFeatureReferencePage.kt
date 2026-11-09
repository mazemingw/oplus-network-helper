package dev.qcom.efs

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.qcom.efs.features.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun ModemFeatureReferenceScreen(slot: Int, originals: Map<String, List<OriginalItem>>, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    Scaffold(topBar = { TopAppBar(title = { Text("属性值与 EFS 恢复参考") }, navigationIcon = {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回基带功能") }
    }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("卡${slot + 1} · 十六进制字节", style = MaterialTheme.typography.titleMedium)
                Text("此页只读，可长按复制。卡2路径带 _Subscription01 后缀。开启时恢复本机、本卡槽的原值；原来没有该文件时删除该项，不要把所有属性都当作 0/1 开关。", style = MaterialTheme.typography.bodySmall)
                Text("手动 edit EFS 前请保留备份。三项 MIMO/4Rx 共用文件，不能独立套用模板；写回后需保存应用或重启基带。", style = MaterialTheme.typography.bodySmall)
            }
            items(ALL_FEATURES, key = { it.id }) { feature ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(feature.label.removePrefix("禁用 "), style = MaterialTheme.typography.titleSmall)
                        Text(featureRisk(feature.id), style = MaterialTheme.typography.bodySmall)
                        featureValueReferences(feature, slot, originals[feature.id].orEmpty()).forEach { value ->
                            HorizontalDivider()
                            SelectionContainer { Text(value.path, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("开启 / 恢复", Modifier.width(82.dp), style = MaterialTheme.typography.labelMedium)
                                SelectionContainer { Text(value.enabled, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("关闭 / 禁用", Modifier.width(82.dp), style = MaterialTheme.typography.labelMedium)
                                SelectionContainer { Text(value.disabled, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                            }
                            value.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }
        }
    }
}

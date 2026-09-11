package com.nvmex.networkhelper.hotspot.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nvmex.networkhelper.R
import com.nvmex.networkhelper.hotspot.utils.DefaultRegDomains
import com.nvmex.networkhelper.hotspot.utils.RegDomain

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CountryPickerDialog(
    current: String?,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
    domains: List<RegDomain> = DefaultRegDomains
) {
    var query by remember { mutableStateOf("") }
    val experimentalText = stringResource(R.string.hotspot_country_experimental)
    val locale = LocalConfiguration.current.locales[0]
    val useChineseName = locale.language.equals("zh", ignoreCase = true)

    val filtered = remember(query, domains) {
        val q = query.trim()
        if (q.isEmpty()) domains
        else domains.filter {
            it.code.contains(q, ignoreCase = true) ||
                    it.nameZh.contains(q, ignoreCase = true) ||
                    it.nameEn.contains(q, ignoreCase = true) ||
                    (it.note?.contains(q, ignoreCase = true) == true)
        }
    }

    val curUpper = remember(current) { current?.uppercase() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.hotspot_country_dialog_title))
//                Text(
//                    "将执行：cmd wifi force-country-code enabled XX",
//                    style = MaterialTheme.typography.bodySmall,
//                    color = MaterialTheme.colorScheme.onSurfaceVariant
//                )
            }
        },
        text = {
            // ✅ 关键：内容区可滚动 + 限制最大高度，避免对话框过高
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {

                // 搜索框
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.hotspot_country_search_hint)) },
                    modifier = Modifier.fillMaxWidth()
                )

                // 当前选择提示
                if (!curUpper.isNullOrBlank()) {
                    val hit = domains.firstOrNull { it.code.equals(curUpper, ignoreCase = true) }
                    Text(
                        text = stringResource(R.string.hotspot_country_current, buildString {
                            if (hit != null) {
                                append("${hit.displayName(useChineseName)} (${hit.code})")
                                if (!hit.note.isNullOrBlank()) append(" · ${hit.note}")
                                if (hit.experimental) append(" · $experimentalText")
                            } else {
                                append(curUpper)
                            }
                        }),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // 选项列表：FlowRow + AssistChip
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    filtered.forEach { item ->
                        val selected = item.code.equals(curUpper, ignoreCase = true)

                        val labelText = buildString {
                            append(item.displayName(useChineseName))
                            append("  ")
                            append(item.code.uppercase())
                            if (!item.note.isNullOrBlank()) append(" · ${item.note}")
                            if (item.experimental) append(" · $experimentalText")
                        }

                        AssistChip(
                            onClick = { onPick(item.code.uppercase()) },
                            label = {
                                Text(
                                    text = labelText,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                                )
                            }
                        )
                    }
                }

                if (filtered.isEmpty()) {
                    Text(
                        stringResource(R.string.hotspot_country_no_match),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Text(
                    stringResource(R.string.hotspot_country_tip),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        }
    )
}

private fun RegDomain.displayName(useChineseName: Boolean): String {
    return if (useChineseName) nameZh else nameEn
}

package com.nvmex.networkhelper.ui.settings.fonts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nvmex.networkhelper.R
import com.nvmex.networkhelper.ui.network.KeyValueRow
import com.nvmex.networkhelper.ui.network.sections.CaChip
import com.nvmex.networkhelper.ui.network.sections.KeyChipsRow

@Composable
fun FontSizeRealPreview() {
    // 模拟 CA 芯片数据
    val mockChips = remember {
        listOf(
            CaChip(
                text = "79 · 100M",  // 主载波
                active = true
            ),
            CaChip(
                text = "41 · 100M",  // 辅载波1 - 激活
                active = true
            ),
            CaChip(
                text = "258 · 400M",  // 辅载波2 - 未激活
                active = false
            )
        )
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(420.dp)  // 稍微增加高度
            .clip(MaterialTheme.shapes.medium),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // =========================
            // 顶部标题行
            // =========================
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = stringResource(R.string.font_preview_title),
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "NR",
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
            }

            // 主要信息行
            KeyValueRow(stringResource(R.string.network_operator), stringResource(R.string.font_preview_operator))
            KeyValueRow("MCC/MNC", "460 / 00")

            HorizontalDivider()

            KeyValueRow(stringResource(R.string.network_data_nr_mode), "NR-SA")
            KeyValueRow(stringResource(R.string.network_cell_type), "NR")
            KeyValueRow("FREQ ▲▼", "4827.36 MHz")

            HorizontalDivider()

            // 服务基站行
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.network_serving_cell),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(R.string.font_preview_serving_cell),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF448AFF)
                )
            }

            // TAC/PCI/CI/ARFCN
            KeyValueRow("NR-TAC", "3407922")
            KeyValueRow("NR-PCI", "560")
            KeyValueRow("NR-NCI", "17184325653(4195392-21)")
            KeyValueRow("NR-ARFCN", "721824")
            KeyValueRow(stringResource(R.string.network_total_active_bandwidth), "600 / 200MHz")
            // 频段/双工模式
            KeyValueRow(
                stringResource(R.string.network_band_duplex),
                "N79 / TDD",
                valueColor = Color(0xFF448AFF)
            )

            // ===== NR CA 行 - 使用 KeyChipsRow =====
            KeyChipsRow(
                k = "NR CA",
                chips = mockChips,
                activeColor = Color(0xFF448AFF),  // 蓝色表示激活
                inactiveColor = Color(0xFFFF5722), // 橙色表示未激活
                onClick = { /* 模拟点击查看详情 */ }
            )

            HorizontalDivider()
        }
    }
}

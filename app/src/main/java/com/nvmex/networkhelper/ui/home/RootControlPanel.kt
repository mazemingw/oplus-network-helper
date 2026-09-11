package com.nvmex.networkhelper.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nvmex.networkhelper.R
import com.nvmex.networkhelper.util.home.OplusVendor

@Composable
fun RootControlPanel(
    hasRoot: Boolean?,
    vendor: OplusVendor,
    onRootStart: () -> Unit,
    onRootForceStop: () -> Unit,
    onShowBandHelp: () -> Unit,
    onCheckSuEnv: () -> Unit,
    modifier: Modifier = Modifier
) {
    // =========================
    // 0) 状态判定：支持/Root/最终可用
    // =========================
    val vendorSupported = vendor != OplusVendor.OTHER
    val rootOk = (hasRoot == true)
    val enabled = vendorSupported && rootOk

    // =========================
    // 1) 展示用：label
    // =========================
    val vendorLabel = when (vendor) {
        OplusVendor.ONEPLUS -> "OnePlus"
        OplusVendor.REALME -> "realme"
        OplusVendor.OPPO -> "OPPO"
        OplusVendor.OPLUS_GENERIC -> "OPlus"
        OplusVendor.OTHER -> "Other"
    }

    val outlineColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.85f)

    // ✅ 统一文字色（更干净）
    val mainTextColor = MaterialTheme.colorScheme.onSurface
    val hintTextColor = MaterialTheme.colorScheme.onSurfaceVariant

    // =========================
    // 2) 小圆点组件（彩色状态灯）
    // =========================
    @Composable
    fun StatusDot(
        ok: Boolean?,
        modifier: Modifier = Modifier
    ) {
        val c = when (ok) {
            true -> Color(0xFF4CAF50)   // ✅ 绿色：OK / 支持
            false -> Color(0xFFF44336)  // ❌ 红色：失败 / 不支持
            null -> Color(0xFF9E9E9E)   // ⏳ 灰色：检测中 / 未知
        }

        Box(
            modifier = modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(c)
        )
    }


    // 厂商点：支持=true / 不支持=false（不需要 null）
    @Composable
    fun VendorDot(
        supported: Boolean,
        modifier: Modifier = Modifier
    ) {
        val c = if (supported) {
            Color(0xFF4CAF50)   // ✅ 绿色：欧加系支持
        } else {
            Color(0xFFF44336)   // ❌ 红色：非欧加
        }

        Box(
            modifier = modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(c)
        )
    }


    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {

            // =========================
            // 3) 左标题 + 右侧状态
            // =========================
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.home_engineer_control_title),
                    color = mainTextColor,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(start = 12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "${stringResource(R.string.home_model_label)} $vendorLabel",
                            color = mainTextColor,
                            style = MaterialTheme.typography.labelMedium,
                            textAlign = TextAlign.End,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.width(8.dp))
                        VendorDot(vendorSupported)
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.home_root_label),
                            color = mainTextColor,
                            style = MaterialTheme.typography.labelMedium,
                            textAlign = TextAlign.End,
                            maxLines = 1
                        )
                        Spacer(Modifier.width(8.dp))
                        StatusDot(ok = hasRoot)
                    }
                }
            }

            // =========================
            // 4) 异常提示：只在必要时出现
            // =========================
            if (!vendorSupported) {
                Text(
                    text = stringResource(R.string.home_vendor_unsupported_hint),
                    color = hintTextColor,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (hasRoot == false) {
                Text(
                    text = stringResource(R.string.home_root_hint),
                    color = hintTextColor,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            // =========================
            // 5) 第一排：实心按钮
            // =========================
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = onRootStart,
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF448AFF),
                        contentColor = Color.White,
                        disabledContainerColor = Color(0xFF448AFF).copy(alpha = 0.4f),
                        disabledContentColor = Color.White.copy(alpha = 0.6f)
                    )
                ) {
                    Text(
                        when {
                            !vendorSupported -> stringResource(R.string.home_device_unsupported)
                            hasRoot == false -> stringResource(R.string.home_need_root)
                            hasRoot == null -> stringResource(R.string.home_checking)
                            else -> stringResource(R.string.home_start_engineer)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Button(
                    onClick = onRootForceStop,
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                        disabledContainerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.4f),
                        disabledContentColor = MaterialTheme.colorScheme.onError.copy(alpha = 0.6f)
                    )
                ) {
                    Text(
                        stringResource(R.string.home_stop_engineer),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            // =========================
            // 6) 第二排：Outline 按钮
            // =========================
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onShowBandHelp,
                    modifier = Modifier.weight(1f),
                    border = BorderStroke(1.dp, outlineColor),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = mainTextColor
                    )
                ) {
                    Text(
                        stringResource(R.string.home_supported_bands),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                OutlinedButton(
                    onClick = onCheckSuEnv,
                    modifier = Modifier.weight(1f),
                    border = BorderStroke(1.dp, outlineColor),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = mainTextColor
                    )
                ) {
                    Text(
                        stringResource(R.string.home_check_support),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}



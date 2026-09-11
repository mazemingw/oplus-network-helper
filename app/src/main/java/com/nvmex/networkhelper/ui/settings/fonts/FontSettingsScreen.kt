package com.nvmex.networkhelper.ui.settings.fonts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nvmex.networkhelper.R
import com.nvmex.networkhelper.util.windows.WindowUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FontSettingsScreen(
    onBackPressed: () -> Unit
) {
    val currentLevel by FontSizeConfig.currentLevel.collectAsState()
    val context = LocalContext.current
    val statusBarHeight = WindowUtils.getStatusBarHeight(context)

    // 将枚举值映射到滑动条的位置 (0-5)
    val sliderPositions = remember {
        FontSizeConfig.FontSizeLevel.entries.mapIndexed { index, level ->
            level to index.toFloat()
        }.toMap()
    }

    // 当前滑动条位置
    val currentSliderPosition = remember(currentLevel) {
        sliderPositions[currentLevel] ?: 2f // 默认中号是2
    }

    // 滑动条变化回调
    var sliderValue by remember { mutableFloatStateOf(currentSliderPosition) }

    Scaffold(

    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
            .padding(top = statusBarHeight),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 字体大小设置部分
            item {
                Text(
                    text = stringResource(R.string.font_size_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            // 预览区域
            item {
                FontSizeRealPreview()
            }

            // 滑动条区域
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // 当前档位显示
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.font_size_current),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                // 当前档位的emoji
                                val levelEmoji = when(currentLevel.scale) {
                                    0.6f -> "🔹"
                                    0.8f -> "🔸"
                                    1.0f -> "⚪"
                                    1.2f -> "🔵"
                                    1.4f -> "🟣"
                                    1.6f -> "🔴"
                                    else -> "•"
                                }

                                Text(
                                    text = "$levelEmoji ${fontSizeLevelName(currentLevel)}",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )

                                Text(
                                    text = "(${fontSizeLevelDescription(currentLevel)})",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        // 滑动条
                        Slider(
                            value = sliderValue,
                            onValueChange = { newValue ->
                                sliderValue = newValue
                                // 根据滑动位置选择对应的档位
                                val index = newValue.toInt().coerceIn(0, FontSizeConfig.FontSizeLevel.entries.size - 1)
                                val newLevel = FontSizeConfig.FontSizeLevel.entries[index]
                                if (newLevel != currentLevel) {
                                    FontSizeConfig.setLevel(newLevel)
                                }
                            },
                            valueRange = 0f..(FontSizeConfig.FontSizeLevel.entries.size - 1).toFloat(),
                            steps = FontSizeConfig.FontSizeLevel.entries.size - 2, // 步数 = 档位数 - 1
                            colors = SliderDefaults.colors(
                                thumbColor = MaterialTheme.colorScheme.primary,
                                activeTrackColor = MaterialTheme.colorScheme.primary,
                                inactiveTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.24f)
                            )
                        )

                        // 档位标签
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            FontSizeConfig.FontSizeLevel.entries.forEach { level ->
                                Text(
                                    text = fontSizeLevelShortName(level),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (level == currentLevel)
                                        MaterialTheme.colorScheme.primary
                                    else
                                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                    modifier = Modifier.alpha(if (level == currentLevel) 1f else 0.6f)
                                )
                            }
                        }
                    }
                }
            }

            // 额外说明
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                ) {
                    Text(
                        text = stringResource(R.string.font_size_hint),
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

@Composable
private fun fontSizeLevelName(level: FontSizeConfig.FontSizeLevel): String {
    return when (level) {
        FontSizeConfig.FontSizeLevel.EXTRA_SMALL -> stringResource(R.string.font_size_extra_small)
        FontSizeConfig.FontSizeLevel.SMALL -> stringResource(R.string.font_size_small)
        FontSizeConfig.FontSizeLevel.MEDIUM -> stringResource(R.string.font_size_medium)
        FontSizeConfig.FontSizeLevel.LARGE -> stringResource(R.string.font_size_large)
        FontSizeConfig.FontSizeLevel.EXTRA_LARGE -> stringResource(R.string.font_size_extra_large)
        FontSizeConfig.FontSizeLevel.HUGE -> stringResource(R.string.font_size_huge)
    }
}

@Composable
private fun fontSizeLevelShortName(level: FontSizeConfig.FontSizeLevel): String {
    return if (level == FontSizeConfig.FontSizeLevel.MEDIUM) {
        stringResource(R.string.font_size_medium_default)
    } else {
        fontSizeLevelName(level)
    }
}

@Composable
private fun fontSizeLevelDescription(level: FontSizeConfig.FontSizeLevel): String {
    return when (level) {
        FontSizeConfig.FontSizeLevel.EXTRA_SMALL -> stringResource(R.string.font_size_desc_extra_small)
        FontSizeConfig.FontSizeLevel.SMALL -> stringResource(R.string.font_size_desc_small)
        FontSizeConfig.FontSizeLevel.MEDIUM -> stringResource(R.string.font_size_desc_medium)
        FontSizeConfig.FontSizeLevel.LARGE -> stringResource(R.string.font_size_desc_large)
        FontSizeConfig.FontSizeLevel.EXTRA_LARGE -> stringResource(R.string.font_size_desc_extra_large)
        FontSizeConfig.FontSizeLevel.HUGE -> stringResource(R.string.font_size_desc_huge)
    }
}

// 原来的 FontSizeOption 可以删除了，或者保留作为备用

package com.nvmex.networkhelper.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.nvmex.networkhelper.ui.settings.fonts.FontSizeConfig

private val DarkColorScheme = darkColorScheme(
    primary = Purple80,
    secondary = PurpleGrey80,
    tertiary = Pink80
)

private val LightColorScheme = lightColorScheme(
    primary = Purple40,
    secondary = PurpleGrey40,
    tertiary = Pink40
)

@Composable
fun NetworkHelperTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    // 监听字体大小变化
    val fontSizeLevel by FontSizeConfig.currentLevel.collectAsState()

    // 根据当前缩放比例创建字体样式
    val scaledTypography = remember(fontSizeLevel) {
        createScaledTypography(fontSizeLevel.scale)
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = scaledTypography,  // 使用动态缩放的字体
        content = content
    )
}

// 创建带缩放的 Typography
private fun createScaledTypography(scale: Float): Typography {
    return Typography(
        // Display 样式 - 大标题
        displayLarge = TextStyle(
            fontSize = (57 * scale).sp,
            lineHeight = (64 * scale).sp,
            fontWeight = FontWeight.Normal
        ),
        displayMedium = TextStyle(
            fontSize = (45 * scale).sp,
            lineHeight = (52 * scale).sp,
            fontWeight = FontWeight.Normal
        ),
        displaySmall = TextStyle(
            fontSize = (36 * scale).sp,
            lineHeight = (44 * scale).sp,
            fontWeight = FontWeight.Normal
        ),

        // Headline 样式 - 标题
        headlineLarge = TextStyle(
            fontSize = (32 * scale).sp,
            lineHeight = (40 * scale).sp,
            fontWeight = FontWeight.SemiBold
        ),
        headlineMedium = TextStyle(
            fontSize = (28 * scale).sp,
            lineHeight = (36 * scale).sp,
            fontWeight = FontWeight.SemiBold
        ),
        headlineSmall = TextStyle(
            fontSize = (24 * scale).sp,
            lineHeight = (32 * scale).sp,
            fontWeight = FontWeight.SemiBold
        ),

        // Title 样式 - 次级标题
        titleLarge = TextStyle(
            fontSize = (22 * scale).sp,
            lineHeight = (28 * scale).sp,
            fontWeight = FontWeight.Medium
        ),
        titleMedium = TextStyle(
            fontSize = (16 * scale).sp,
            lineHeight = (24 * scale).sp,
            fontWeight = FontWeight.Medium
        ),
        titleSmall = TextStyle(
            fontSize = (14 * scale).sp,
            lineHeight = (20 * scale).sp,
            fontWeight = FontWeight.Medium
        ),

        // Body 样式 - 正文（你最关心的部分）
        bodyLarge = TextStyle(
            fontSize = (16 * scale).sp,
            lineHeight = (24 * scale).sp,
            fontWeight = FontWeight.Normal
        ),
        bodyMedium = TextStyle(
            fontSize = (14 * scale).sp,
            lineHeight = (20 * scale).sp,
            fontWeight = FontWeight.Normal
        ),
        bodySmall = TextStyle(
            fontSize = (12 * scale).sp,
            lineHeight = (16 * scale).sp,
            fontWeight = FontWeight.Normal
        ),

        // Label 样式 - 标签
        labelLarge = TextStyle(
            fontSize = (14 * scale).sp,
            lineHeight = (20 * scale).sp,
            fontWeight = FontWeight.Medium
        ),
        labelMedium = TextStyle(
            fontSize = (12 * scale).sp,
            lineHeight = (16 * scale).sp,
            fontWeight = FontWeight.Medium
        ),
        labelSmall = TextStyle(
            fontSize = (11 * scale).sp,
            lineHeight = (16 * scale).sp,
            fontWeight = FontWeight.Medium
        )
    )
}

// 如果你需要保留原来的 Typography 定义（可能在其他地方用到）
// 可以在这里定义基础的 Typography，但上面的函数会覆盖它
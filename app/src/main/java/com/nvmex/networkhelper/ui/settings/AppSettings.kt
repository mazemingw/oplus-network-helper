package com.nvmex.networkhelper.ui.settings

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.edit
import com.nvmex.networkhelper.xposed.logger.Logger
import java.io.File

data class AppSettings(
    val enableSignalChart: Boolean = true,
    val enableEChartsChart: Boolean = false,
    val autoRangeChart: Boolean = false,
    val enablePluginHelp: Boolean = true,
    val enableRealSignalIcon: Boolean = true,
    val enableCellTimeline: Boolean = false,
    val hideHomeTopBar: Boolean = false,
    val customBackgroundPath: String = "",
    val customBackgroundAlpha: Float = 0.22f,
    val suppressEngineerGuidePrompt: Boolean = false
)

object AppSettingsStore {
    private const val SP_NAME = "nh_settings"
    private const val KEY_ENABLE_CHART = "enable_signal_chart"
    private const val KEY_ENABLE_ECHARTS_CHART = "enable_echarts_chart"
    private const val KEY_AUTO_RANGE = "auto_range_chart"
    private const val KEY_ENABLE_PLUGIN_HELP = "enable_plugin_help"
    private const val KEY_ENABLE_REAL_SIGNAL_ICON = "enable_real_signal_icon"
    private const val KEY_ENABLE_CELL_TIMELINE = "enable_cell_timeline"
    private const val KEY_HIDE_HOME_TOP_BAR = "hide_home_top_bar"
    private const val KEY_CUSTOM_BACKGROUND_PATH = "custom_background_path"
    private const val KEY_CUSTOM_BACKGROUND_ALPHA = "custom_background_alpha"
    private const val KEY_SUPPRESS_ENGINEER_GUIDE_PROMPT = "suppress_engineer_guide_prompt"

    // ✅ 内部存储目录和文件名（Xposed模块将直接读取这个路径）
    private const val XPOSED_CONFIG_DIR = "xposed_config"
    private const val XPOSED_CONFIG_FILE = "plugin_help.txt"
    private const val BACKGROUND_DIR = "custom_background"
    private const val BACKGROUND_FILE = "background_image"

    private val memoryState = mutableStateOf<AppSettings?>(null)

    fun read(context: Context): AppSettings {
        val sp = context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
        return AppSettings(
            enableSignalChart = sp.getBoolean(KEY_ENABLE_CHART, true),
            enableEChartsChart = sp.getBoolean(KEY_ENABLE_ECHARTS_CHART, false),
            autoRangeChart = sp.getBoolean(KEY_AUTO_RANGE, false),
            enablePluginHelp = sp.getBoolean(KEY_ENABLE_PLUGIN_HELP, true),
            enableRealSignalIcon = sp.getBoolean(KEY_ENABLE_REAL_SIGNAL_ICON, true),
            enableCellTimeline = sp.getBoolean(KEY_ENABLE_CELL_TIMELINE, false),
            hideHomeTopBar = sp.getBoolean(KEY_HIDE_HOME_TOP_BAR, false),
            customBackgroundPath = sp.getString(KEY_CUSTOM_BACKGROUND_PATH, "").orEmpty(),
            customBackgroundAlpha = sp.getFloat(KEY_CUSTOM_BACKGROUND_ALPHA, 0.22f).coerceIn(0f, 1f),
            suppressEngineerGuidePrompt = sp.getBoolean(KEY_SUPPRESS_ENGINEER_GUIDE_PROMPT, false)
        )
    }

    fun current(context: Context): AppSettings {
        val existing = memoryState.value
        if (existing != null) return existing
        return read(context).also { memoryState.value = it }
    }

    fun write(context: Context, settings: AppSettings) {
        memoryState.value = settings
        setEnableSignalChart(context, settings.enableSignalChart)
        setEnableEChartsChart(context, settings.enableEChartsChart)
        setAutoRangeChart(context, settings.autoRangeChart)
        setEnablePluginHelp(context, settings.enablePluginHelp)
        setEnableRealSignalIcon(context, settings.enableRealSignalIcon)
        setEnableCellTimeline(context, settings.enableCellTimeline)
        setHideHomeTopBar(context, settings.hideHomeTopBar)
        setCustomBackgroundPath(context, settings.customBackgroundPath)
        setCustomBackgroundAlpha(context, settings.customBackgroundAlpha)
        setSuppressEngineerGuidePrompt(context, settings.suppressEngineerGuidePrompt)
    }

    fun setEnableSignalChart(context: Context, value: Boolean) {
        context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
            .edit {
                putBoolean(KEY_ENABLE_CHART, value)
            }
    }

    fun setEnableEChartsChart(context: Context, value: Boolean) {
        context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
            .edit {
                putBoolean(KEY_ENABLE_ECHARTS_CHART, value)
            }
    }

    fun setAutoRangeChart(context: Context, value: Boolean) {
        context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
            .edit {
                putBoolean(KEY_AUTO_RANGE, value)
            }
    }

    fun setEnablePluginHelp(context: Context, value: Boolean) {

        val sp = context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)

        // ⭐ 使用 commit 确保落盘
        sp.edit().putBoolean(KEY_ENABLE_PLUGIN_HELP, value).commit()

        writePluginHelpFile(context, value)
    }

    fun setEnableRealSignalIcon(context: Context, value: Boolean) {
        context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
            .edit {
                putBoolean(KEY_ENABLE_REAL_SIGNAL_ICON, value)
            }
    }

    fun setEnableCellTimeline(context: Context, value: Boolean) {
        context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
            .edit {
                putBoolean(KEY_ENABLE_CELL_TIMELINE, value)
            }
    }

    fun setHideHomeTopBar(context: Context, value: Boolean) {
        context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
            .edit {
                putBoolean(KEY_HIDE_HOME_TOP_BAR, value)
            }
    }

    fun setCustomBackgroundPath(context: Context, value: String) {
        context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
            .edit {
                putString(KEY_CUSTOM_BACKGROUND_PATH, value)
            }
    }

    fun setCustomBackgroundAlpha(context: Context, value: Float) {
        context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
            .edit {
                putFloat(KEY_CUSTOM_BACKGROUND_ALPHA, value.coerceIn(0f, 1f))
            }
    }

    fun setSuppressEngineerGuidePrompt(context: Context, value: Boolean) {
        context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
            .edit {
                putBoolean(KEY_SUPPRESS_ENGINEER_GUIDE_PROMPT, value)
            }
    }

    private fun writePluginHelpFile(context: Context, value: Boolean) {
        try {

            val dir = File(context.filesDir, XPOSED_CONFIG_DIR)
            if (!dir.exists()) dir.mkdirs()

            val file = File(dir, XPOSED_CONFIG_FILE)

            file.writeText("enable_plugin_help=$value")

            file.setReadable(true, false)

            Logger.log("✅ 写入Xposed配置成功: $value")

        } catch (e: Throwable) {
            Logger.logE("写入Xposed配置失败", e)
        }
    }

    fun importCustomBackground(context: Context, uri: Uri): String? {
        return runCatching {
            val dir = File(context.filesDir, BACKGROUND_DIR)
            if (!dir.exists()) dir.mkdirs()

            dir.listFiles()?.forEach { file ->
                runCatching { file.delete() }
            }

            val target = File(dir, BACKGROUND_FILE)
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: return null

            target.takeIf { it.exists() && it.length() > 0L }?.absolutePath
        }.getOrNull()
    }

    fun clearCustomBackground(context: Context) {
        val path = read(context).customBackgroundPath
        if (path.isNotBlank()) {
            runCatching { File(path).delete() }
        }
        runCatching {
            File(context.filesDir, BACKGROUND_DIR).listFiles()?.forEach { file ->
                file.delete()
            }
        }
        val updated = current(context).copy(customBackgroundPath = "")
        write(context, updated)
    }

}

/**
 * ✅ Compose 里用这个拿到可响应的设置状态
 */
@Composable
fun rememberAppSettings(): Pair<AppSettings, (AppSettings) -> Unit> {
    val ctx = LocalContext.current
    val settings = AppSettingsStore.current(ctx)

    val setter: (AppSettings) -> Unit = remember {
        { new ->
            AppSettingsStore.write(ctx, new)
        }
    }

    return settings to setter
}

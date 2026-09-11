package com.nvmex.networkhelper.ui.settings

import android.content.Context
import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import com.nvmex.networkhelper.R
import com.nvmex.networkhelper.model.home.UpdateUiState
import com.nvmex.networkhelper.ui.navigation.Routes
import com.nvmex.networkhelper.ui.base.toast
import com.nvmex.networkhelper.ui.home.UpdateDialog
import com.nvmex.networkhelper.ui.settings.fonts.FontSizeConfig
import com.nvmex.networkhelper.ui.settings.sections.SwitchSettingItem
import com.nvmex.networkhelper.viewmodel.home.UpdateViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(
    navController: NavHostController,
    contentBottomPadding: Dp = 0.dp
) {
    val context = LocalContext.current
    val (settings, setSettings) = rememberAppSettings()
    val currentFontLevel by FontSizeConfig.currentLevel.collectAsState()
    val updateVm: UpdateViewModel = hiltViewModel()
    val updateUi by updateVm.ui.collectAsState()
    val downloadProgress by updateVm.downloadProgress.collectAsState()
    val downloadSpeed by updateVm.downloadSpeed.collectAsState()
    var showLanguageDialog by remember { mutableStateOf(false) }
    var manualUpdateCheckPending by remember { mutableStateOf(false) }
    var languageTag by remember { mutableStateOf(AppLanguageManager.getLanguageTag(context)) }
    val currentLanguageLabel = languageLabel(languageTag)
    val scope = rememberCoroutineScope()
    val imageImportFailed = stringResource(R.string.settings_background_import_failed)
    val updateLatestText = stringResource(R.string.settings_update_latest)
    val updateCheckingText = stringResource(R.string.settings_update_checking)
    val updateErrorText = (updateUi as? UpdateUiState.Error)?.let {
        stringResource(R.string.state_error_prefix, it.msg)
    }
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val path = withContext(Dispatchers.IO) {
                AppSettingsStore.importCustomBackground(context, uri)
            }
            if (path == null) {
                toast(context, imageImportFailed)
            } else {
                setSettings(settings.copy(customBackgroundPath = path))
            }
        }
    }

    LaunchedEffect(updateUi) {
        when (val state = updateUi) {
            is UpdateUiState.Available -> {
                manualUpdateCheckPending = false
            }
            is UpdateUiState.NoUpdate -> {
                if (manualUpdateCheckPending) {
                    manualUpdateCheckPending = false
                    toast(context, updateLatestText)
                }
            }
            is UpdateUiState.Error -> {
                if (manualUpdateCheckPending) {
                    manualUpdateCheckPending = false
                    toast(context, updateErrorText ?: state.msg)
                }
            }
            else -> Unit
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
        contentPadding = PaddingValues(
            start = 18.dp,
            end = 18.dp,
            top = 12.dp,
            bottom = 24.dp + contentBottomPadding
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Column {
                Text(
                    text = stringResource(R.string.nav_settings),
                    style = MaterialTheme.typography.headlineSmall
                )
                Spacer(modifier = Modifier.height(6.dp))
            }
        }


        item {
            ThinListContainer {
                SwitchSettingItem(
                    title = stringResource(R.string.settings_real_signal_icon_title),
                    desc = stringResource(R.string.settings_real_signal_icon_desc),
                    checked = settings.enableRealSignalIcon,
                    onCheckedChange = { on ->
                        setSettings(settings.copy(enableRealSignalIcon = on))
                    }
                )

                ThinDivider()

                SwitchSettingItem(
                    title = stringResource(R.string.settings_signal_chart_title),
                    desc = stringResource(R.string.settings_signal_chart_desc),
                    checked = settings.enableSignalChart,
                    onCheckedChange = { on ->
                        val new = settings.copy(
                            enableSignalChart = on,
                            autoRangeChart = if (!on) false else settings.autoRangeChart
                        )
                        setSettings(new)
                    }
                )

                ThinDivider()

                SwitchSettingItem(
                    title = stringResource(R.string.settings_echarts_chart_title),
                    desc = stringResource(R.string.settings_echarts_chart_desc),
                    checked = settings.enableEChartsChart,
                    onCheckedChange = { on ->
                        setSettings(settings.copy(enableEChartsChart = on))
                    }
                )

                ThinDivider()

                SwitchSettingItem(
                    title = stringResource(R.string.settings_cell_timeline_title),
                    desc = stringResource(R.string.settings_cell_timeline_desc),
                    checked = settings.enableCellTimeline,
                    onCheckedChange = { on ->
                        setSettings(settings.copy(enableCellTimeline = on))
                    }
                )

                ThinDivider()

                SwitchSettingItem(
                    title = stringResource(R.string.settings_hide_home_top_bar_title),
                    desc = stringResource(R.string.settings_hide_home_top_bar_desc),
                    checked = settings.hideHomeTopBar,
                    onCheckedChange = { on ->
                        setSettings(settings.copy(hideHomeTopBar = on))
                    }
                )

                ThinDivider()

                SwitchSettingItem(
                    title = stringResource(R.string.settings_high_precision_chart_title),
                    desc = stringResource(R.string.settings_high_precision_chart_desc),
                    checked = settings.autoRangeChart,
                    onCheckedChange = { on ->
                        val new = settings.copy(
                            enableSignalChart = if (on) true else settings.enableSignalChart,
                            autoRangeChart = on
                        )
                        setSettings(new)
                    }
                )

                ThinDivider()

                SwitchSettingItem(
                    title = stringResource(R.string.settings_plugin_help_title),
                    desc = stringResource(R.string.settings_plugin_help_desc),
                    checked = settings.enablePluginHelp,
                    onCheckedChange = { on ->
                        setSettings(settings.copy(enablePluginHelp = on))
                    }
                )
            }
        }

        item {
            BackgroundSettingCard(
                imagePath = settings.customBackgroundPath,
                alpha = settings.customBackgroundAlpha,
                onImport = { imagePickerLauncher.launch("image/*") },
                onClear = {
                    AppSettingsStore.clearCustomBackground(context)
                    setSettings(settings.copy(customBackgroundPath = ""))
                },
                onAlphaChange = { value ->
                    setSettings(settings.copy(customBackgroundAlpha = value))
                }
            )
        }

        item {
            SectionTitle(
                title = stringResource(R.string.settings_general_title),
                subtitle = ""
            )
        }

        item {
            ThinListContainer {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SettingsEntryRow(
                        title = stringResource(R.string.settings_usage_tutorial_title),
                        subtitle = stringResource(R.string.settings_usage_tutorial_subtitle),
                        highlight = true,
                        onClick = {
                            openUrl(context, USAGE_TUTORIAL_URL)
                        }
                    )

                    SettingsEntryRow(
                        title = stringResource(R.string.settings_join_qq_title),
                        subtitle = stringResource(R.string.settings_join_qq_subtitle),
                        highlight = true,
                        onClick = {
                            openUrl(context, "https://qm.qq.com/q/Xn20UNODaS")
                        }
                    )

                    SettingsEntryRow(
                        title = stringResource(R.string.settings_coolapk_title),
                        subtitle = stringResource(R.string.settings_coolapk_subtitle),
                        highlight = true,
                        onClick = {
                            openUrl(context, "https://www.coolapk.com/u/1060215")
                        }
                    )

                    SettingsEntryRow(
                        title = stringResource(R.string.settings_github_title),
                        subtitle = stringResource(R.string.settings_github_subtitle),
                        highlight = true,
                        onClick = {
                            openUrl(context, "https://github.com/mazemingw/oplus-network-helper")
                        }
                    )

                    SettingsEntryRow(
                        title = stringResource(R.string.settings_font_size_title),
                        subtitle = stringResource(R.string.settings_font_size_current, settingsFontSizeLevelName(currentFontLevel)),
                        highlight = true,
                        onClick = {
                            navController.navigate(Routes.FONT_SETTINGS)
                        }
                    )

                    SettingsEntryRow(
                        title = stringResource(R.string.settings_language_title),
                        subtitle = stringResource(R.string.settings_language_subtitle, currentLanguageLabel),
                        highlight = true,
                        onClick = {
                            showLanguageDialog = true
                        }
                    )

                    SettingsEntryRow(
                        title = stringResource(R.string.settings_version_title),
                        subtitle = if (updateUi is UpdateUiState.Checking) {
                            updateCheckingText
                        } else {
                            stringResource(
                                R.string.settings_version_subtitle,
                                getAppVersionLabel(context)
                            )
                        },
                        highlight = true,
                        onClick = {
                            if (updateUi !is UpdateUiState.Available) {
                                manualUpdateCheckPending = true
                                updateVm.checkOnce()
                            }
                        }
                    )

                    SettingsEntryRow(
                        title = stringResource(R.string.settings_sponsor_title),
                        subtitle = stringResource(R.string.settings_sponsor_subtitle),
                        highlight = false,
                        onClick = {
                            // TODO: 这里接你的赞助逻辑
                        }
                    )
                }
            }

        }
    }

    if (showLanguageDialog) {
        LanguagePickerDialog(
            selectedTag = languageTag,
            onSelect = { tag ->
                AppLanguageManager.setLanguageTag(context, tag)
                languageTag = tag
                showLanguageDialog = false
                context.findActivity()?.recreate()
            },
            onDismiss = { showLanguageDialog = false }
        )
    }

    when (val state = updateUi) {
        is UpdateUiState.Available -> {
            UpdateDialog(
                info = state.info,
                mandatory = state.mandatory,
                downloadProgress = downloadProgress,
                downloadSpeed = downloadSpeed,
                onDismiss = { updateVm.dismissIfAllowed() },
                onSkipThisVersion = { updateVm.skipCurrentVersion() },
                onUpdate = {
                    updateVm.downloadAndInstallApk(context)
                }
            )
        }
        else -> Unit
    }
}

@Composable
private fun BackgroundSettingCard(
    imagePath: String,
    alpha: Float,
    onImport: () -> Unit,
    onClear: () -> Unit,
    onAlphaChange: (Float) -> Unit
) {
    val hasImage = imagePath.isNotBlank()
    val imageBitmap = remember(imagePath) {
        imagePath.takeIf { it.isNotBlank() }
            ?.let { BitmapFactory.decodeFile(it) }
            ?.asImageBitmap()
    }

    ThinListContainer {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column {
                Text(
                    text = stringResource(R.string.settings_background_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.settings_background_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.background),
                contentAlignment = Alignment.Center
            ) {
                if (imageBitmap != null) {
                    Image(
                        bitmap = imageBitmap,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        alpha = alpha.coerceIn(0f, 1f)
                    )
                }
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f),
                    tonalElevation = 0.dp
                ) {
                    Text(
                        text = if (hasImage) {
                            stringResource(R.string.settings_background_preview)
                        } else {
                            stringResource(R.string.settings_background_no_image)
                        },
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            Text(
                text = stringResource(
                    R.string.settings_background_opacity,
                    (alpha.coerceIn(0f, 1f) * 100).toInt()
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Slider(
                value = alpha.coerceIn(0f, 1f),
                onValueChange = onAlphaChange,
                valueRange = 0f..1f,
                enabled = hasImage
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onImport,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.settings_background_import))
                }
                OutlinedButton(
                    onClick = onClear,
                    enabled = hasImage,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.settings_background_clear))
                }
            }
        }
    }
}

private fun getAppVersionLabel(
    context: Context
): String {
    return runCatching {
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(
                context.packageName,
                0
            )
        }

        val versionName = packageInfo.versionName ?: "1.0.0"

        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toLong()
        }

        "$versionName（$versionCode）"
    }.getOrDefault("1.0.0（1）")
}

@Composable
private fun settingsFontSizeLevelName(level: FontSizeConfig.FontSizeLevel): String {
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
private fun LanguagePickerDialog(
    selectedTag: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val options = listOf(
        AppLanguageManager.TAG_SYSTEM to stringResource(R.string.settings_language_system),
        AppLanguageManager.TAG_ZH_CN to stringResource(R.string.settings_language_zh_cn),
        AppLanguageManager.TAG_EN to stringResource(R.string.settings_language_en)
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_language_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                options.forEach { (tag, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { onSelect(tag) }
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selectedTag == tag,
                            onClick = { onSelect(tag) }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Text(
                    text = stringResource(R.string.settings_language_restart_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

@Composable
private fun languageLabel(tag: String): String {
    return when (tag) {
        AppLanguageManager.TAG_ZH_CN -> stringResource(R.string.settings_language_zh_cn)
        AppLanguageManager.TAG_EN -> stringResource(R.string.settings_language_en)
        else -> stringResource(R.string.settings_language_system)
    }
}

@Composable
private fun SectionTitle(
    title: String,
    subtitle: String = ""
) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge
        )

        if (subtitle.isNotBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ThinListContainer(
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 1.dp,
        shadowElevation = 0.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            content = content
        )
    }
}

@Composable
private fun SettingsEntryRow(
    title: String,
    subtitle: String,
    highlight: Boolean = false,
    onClick: () -> Unit
) {
    val rowBg = if (highlight) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.18f)
    } else {
        MaterialTheme.colorScheme.surface
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(rowBg)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(2.dp))

            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.width(10.dp))

        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ThinDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
    )
}

fun openUrl(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
    context.startActivity(intent)
}

private tailrec fun Context.findActivity(): Activity? {
    return when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}

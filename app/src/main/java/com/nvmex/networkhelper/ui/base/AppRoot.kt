package com.nvmex.networkhelper.ui.base


import android.graphics.BitmapFactory
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.nvmex.networkhelper.R
import com.nvmex.networkhelper.hotspot.HotspotScreen
import com.nvmex.networkhelper.iperf.IperfClientScreen
import com.nvmex.networkhelper.iperf.server.IperfServerScreen
import com.nvmex.networkhelper.model.network.NetworkPanelUiState
import com.nvmex.networkhelper.ui.home.HomePlaybackBarState
import com.nvmex.networkhelper.ui.cellparam.ImportCellParamsScreen
import com.nvmex.networkhelper.ui.home.HomeScreen
import com.nvmex.networkhelper.ui.menu.CellQueryScreen
import com.nvmex.networkhelper.ui.menu.BleScannerScreen
import com.nvmex.networkhelper.ui.menu.EngineerBandLockDialog
import com.nvmex.networkhelper.ui.menu.EngineerBandLockScreen
import com.nvmex.networkhelper.ui.menu.FrequencyCalculatorScreen
import com.nvmex.networkhelper.ui.menu.IndoorDriveTestScreen
import com.nvmex.networkhelper.ui.menu.MenuScreen
import com.nvmex.networkhelper.ui.menu.PluginShareScreen
import com.nvmex.networkhelper.ui.navigation.Routes
import com.nvmex.networkhelper.ui.network.SignalComparisonScreen
import com.nvmex.networkhelper.ui.settings.SettingsScreen
import com.nvmex.networkhelper.ui.settings.rememberAppSettings
import com.nvmex.networkhelper.ui.settings.fonts.FontSettingsScreen
import com.nvmex.networkhelper.ui.signalradar.SignalScanScreen
import com.nvmex.networkhelper.ui.wifi.WifiScreen
import com.nvmex.networkhelper.util.home.detectOplusVendor
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File


@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun AppRoot(
    targetPkg: String,
    targetActivity: String,
    navController: NavHostController = rememberNavController()
) {

    var sharedPanelState by remember { mutableStateOf(NetworkPanelUiState()) }
    var playbackBarState by remember { mutableStateOf<HomePlaybackBarState?>(null) }
    var showEngineerBandLockDialog by remember { mutableStateOf(false) }
    val vendor = remember { detectOplusVendor() }
    val hazeState = rememberHazeState()
    val (appSettings, _) = rememberAppSettings()
    val customBackgroundPath = appSettings.customBackgroundPath
        .takeIf { it.isNotBlank() && File(it).exists() }
    val customBackgroundAlpha = appSettings.customBackgroundAlpha.coerceIn(0f, 1f)
    val baseColorScheme = MaterialTheme.colorScheme
    val activeColorScheme = if (customBackgroundPath != null) {
        baseColorScheme.copy(
            surface = baseColorScheme.surface.copy(alpha = 0.84f),
            surfaceVariant = baseColorScheme.surfaceVariant.copy(alpha = 0.74f),
            surfaceContainerLowest = baseColorScheme.surfaceContainerLowest.copy(alpha = 0.66f),
            surfaceContainerLow = baseColorScheme.surfaceContainerLow.copy(alpha = 0.70f),
            surfaceContainer = baseColorScheme.surfaceContainer.copy(alpha = 0.76f),
            surfaceContainerHigh = baseColorScheme.surfaceContainerHigh.copy(alpha = 0.82f),
            surfaceContainerHighest = baseColorScheme.surfaceContainerHighest.copy(alpha = 0.86f)
        )
    } else {
        baseColorScheme
    }
    val pageContentColor = if (activeColorScheme.background.luminance() < 0.5f) {
        Color.White
    } else {
        Color.Black
    }

    data class BottomItem(
        val route: String,
        val label: String,
        val iconResName: String
    )

    val bottomItems = listOf(
        BottomItem(Routes.HOME, stringResource(R.string.nav_home), "home"),
        BottomItem(Routes.RADAR, stringResource(R.string.nav_radar), "radar"),
        BottomItem(Routes.WIFI, stringResource(R.string.nav_wifi), "wifi"),
        BottomItem(Routes.MENU, stringResource(R.string.nav_menu), "menu"),
        BottomItem(Routes.SETTINGS, stringResource(R.string.nav_settings), "settings"),
    )

    // ✅ 当前 route（用 Compose 官方姿势，而不是自己 collect Flow）
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: Routes.HOME

    // ✅ 哪些页面显示底栏（按 route 管，不按 index 管）
    val bottomBarRoutes = remember {
        setOf(
            Routes.HOME,
            Routes.RADAR,
            Routes.WIFI,
            Routes.MENU,
            Routes.SETTINGS
        )
    }
    val showBottomBar = currentRoute in bottomBarRoutes
    val bottomPageInset = 96.dp

    MaterialTheme(colorScheme = activeColorScheme) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            if (customBackgroundPath != null) {
                GlobalBackgroundImage(
                    imagePath = customBackgroundPath,
                    alpha = customBackgroundAlpha
                )
            }

            CompositionLocalProvider(LocalContentColor provides pageContentColor) {
                Scaffold(
                    modifier = Modifier
                        .fillMaxSize(),
                    containerColor = Color.Transparent,
                    contentWindowInsets = WindowInsets(0, 0, 0, 0)
                ) { innerPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    NavHost(
                        navController = navController,
                        startDestination = Routes.HOME,
                        modifier = Modifier
                            .fillMaxSize()
                            .hazeSource(state = hazeState)
                    ) {
            composable(
                Routes.HOME,
                enterTransition = { slideInHorizontally(initialOffsetX = { it }) + fadeIn() },
                exitTransition = { slideOutHorizontally(targetOffsetX = { -it }) + fadeOut() }
            ) {
                HomeScreen(
                    targetPkg = targetPkg,
                    targetActivity = targetActivity,
                    vendor = vendor,
                    onPanelState = { sharedPanelState = it },
                    contentBottomPadding = bottomPageInset,
                    onOpenEngineerBandLock = {
                        showEngineerBandLockDialog = true
                    },
                    onOpenSignalComparison = {
                        navController.navigate(Routes.SIGNAL_COMPARISON) {
                            launchSingleTop = true
                        }
                    },
                    onPlaybackBarChange = { playbackBarState = it }
                )

            }

            composable(
                Routes.RADAR,
                enterTransition = { slideInHorizontally(initialOffsetX = { it }) + fadeIn() },
                exitTransition = { slideOutHorizontally(targetOffsetX = { -it }) + fadeOut() }
            ) {
                SignalScanScreen(contentBottomPadding = bottomPageInset)
            }

            composable(
                Routes.WIFI,
                enterTransition = { slideInHorizontally(initialOffsetX = { it }) + fadeIn() },
                exitTransition = { slideOutHorizontally(targetOffsetX = { -it }) + fadeOut() }
            ) {
                // 你如果叫 WIFISCREEN / WifiScreen / WiFiScreen 都行，按实际改
                WifiScreen(
                    navController = navController,
                    contentBottomPadding = bottomPageInset
                )

            }

            composable(
                Routes.MENU,
                enterTransition = { slideInHorizontally(initialOffsetX = { it }) + fadeIn() },
                exitTransition = { slideOutHorizontally(targetOffsetX = { -it }) + fadeOut() }
            ) {
                MenuScreen(
                    panel = sharedPanelState,
                    navController = navController,
                    contentBottomPadding = bottomPageInset
                )
            }

            composable(
                Routes.ENGINEER_BAND_LOCK,
                enterTransition = { slideInHorizontally(initialOffsetX = { it }) + fadeIn() },
                exitTransition = { slideOutHorizontally(targetOffsetX = { -it }) + fadeOut() }
            ) {
                EngineerBandLockScreen(
                    onBack = { navController.popBackStack() }
                )
            }

            composable(
                Routes.PLUGIN_SHARE,
                enterTransition = { slideInHorizontally(initialOffsetX = { it }) + fadeIn() },
                exitTransition = { slideOutHorizontally(targetOffsetX = { -it }) + fadeOut() }
            ) {
                PluginShareScreen(
                    onBack = { navController.popBackStack() }
                )
            }

            composable(
                Routes.SIGNAL_COMPARISON,
                enterTransition = { slideInHorizontally(initialOffsetX = { it }) + fadeIn() },
                exitTransition = { slideOutHorizontally(targetOffsetX = { -it }) + fadeOut() }
            ) {
                SignalComparisonScreen(
                    onBack = { navController.popBackStack() }
                )
            }

            composable(
                Routes.INDOOR_DRIVE_TEST,
                enterTransition = { slideInHorizontally(initialOffsetX = { it }) + fadeIn() },
                exitTransition = { slideOutHorizontally(targetOffsetX = { -it }) + fadeOut() }
            ) {
                IndoorDriveTestScreen(
                    onBack = { navController.popBackStack() }
                )
            }

            composable(
                Routes.SETTINGS,
                enterTransition = { slideInHorizontally(initialOffsetX = { it }) + fadeIn() },
                exitTransition = { slideOutHorizontally(targetOffsetX = { -it }) + fadeOut() }
            ) {
                SettingsScreen(
                    navController = navController,
                    contentBottomPadding = bottomPageInset
                )
            }

            composable(
                Routes.CELL_QUERY,
                enterTransition = { slideInHorizontally(initialOffsetX = { it }) + fadeIn() },
                exitTransition = { slideOutHorizontally(targetOffsetX = { -it }) + fadeOut() }
            ) {
                CellQueryScreen(panel = sharedPanelState)
            }

            composable(
                Routes.HOTSPOT,
                enterTransition = { slideInVertically(initialOffsetY = { it }) + fadeIn() },
                exitTransition = { slideOutVertically(targetOffsetY = { it }) + fadeOut() },
                popEnterTransition = { slideInVertically(initialOffsetY = { it }) + fadeIn() },
                popExitTransition = { slideOutVertically(targetOffsetY = { it }) + fadeOut() }
            ) {
                HotspotScreen()
            }

            composable(
                Routes.IPERF_SERVER,
                enterTransition = { slideInHorizontally(initialOffsetX = { it }) + fadeIn() },
                exitTransition = { slideOutHorizontally(targetOffsetX = { -it }) + fadeOut() }
            ) {
                IperfServerScreen()
            }

            composable(
                Routes.IPERF_CLIENT,
                enterTransition = { slideInHorizontally(initialOffsetX = { it }) + fadeIn() },
                exitTransition = { slideOutHorizontally(targetOffsetX = { -it }) + fadeOut() }
            ) {
                IperfClientScreen()
            }

            composable(Routes.FONT_SETTINGS,
                enterTransition = { slideInVertically(initialOffsetY = { it }) + fadeIn() },
                exitTransition = { slideOutVertically(targetOffsetY = { it }) + fadeOut() },
                popEnterTransition = { slideInVertically(initialOffsetY = { it }) + fadeIn() },
                popExitTransition = { slideOutVertically(targetOffsetY = { it }) + fadeOut() }
                ) {
                FontSettingsScreen(
                    onBackPressed = {
                        navController.popBackStack()
                    }
                )
            }

            composable(Routes.IMPORT_CELL_PARAMS,
                enterTransition = { slideInVertically(initialOffsetY = { it }) + fadeIn() },
                exitTransition = { slideOutVertically(targetOffsetY = { it }) + fadeOut() },
                popEnterTransition = { slideInVertically(initialOffsetY = { it }) + fadeIn() },
                popExitTransition = { slideOutVertically(targetOffsetY = { it }) + fadeOut() }) {
                ImportCellParamsScreen(navController = navController)
            }

            composable(
                Routes.FREQUENCY_CALCULATOR,
                enterTransition = { slideInHorizontally(initialOffsetX = { it }) + fadeIn() },
                exitTransition = { slideOutHorizontally(targetOffsetX = { -it }) + fadeOut() }
            ) {
                FrequencyCalculatorScreen(
                    onBack = { navController.popBackStack() }
                )
            }

            composable(
                Routes.BLE_SCANNER,
                enterTransition = { slideInHorizontally(initialOffsetX = { it }) + fadeIn() },
                exitTransition = { slideOutHorizontally(targetOffsetX = { -it }) + fadeOut() }
            ) {
                BleScannerScreen(
                    onBack = { navController.popBackStack() }
                )
            }

            }

            EngineerBandLockDialog(
                show = showEngineerBandLockDialog,
                onDismiss = { showEngineerBandLockDialog = false },
                onOpenFullPanel = {
                    showEngineerBandLockDialog = false
                    navController.navigate(Routes.ENGINEER_BAND_LOCK) {
                        launchSingleTop = true
                    }
                }
            )

            val playbackBar = playbackBarState.takeIf { currentRoute == Routes.HOME }
            if (playbackBar != null) {
                PlaybackBottomBar(
                    state = playbackBar,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(start = 18.dp, end = 18.dp, bottom = 14.dp)
                )
            } else if (showBottomBar) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(bottom = 14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth(0.86f)
                            .clip(CircleShape)
                            .hazeEffect(
                                state = hazeState,
                                style = HazeMaterials.ultraThin()
                            ),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.28f),
                        tonalElevation = 0.dp,
                        shadowElevation = 10.dp,
                        border = BorderStroke(
                            width = 0.8.dp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
                        )
                    ) {
                        NavigationBar(
                            modifier = Modifier.height(64.dp),
                            containerColor = Color.Transparent,
                            tonalElevation = 0.dp
                        ) {
                            bottomItems.forEach { item ->
                                val selected = currentRoute == item.route

                                // ✅ 图标只做“资源名 -> id”的映射；不存在就给个兜底，避免炸
                                val ctx = LocalContext.current
                                val iconResId = remember(item.iconResName) {
                                    ctx.resources.getIdentifier(
                                        item.iconResName,
                                        "drawable",
                                        ctx.packageName
                                    )
                                }

                                NavigationBarItem(
                                    selected = selected,
                                    onClick = {
                                        // ✅ 传统做法：点击底栏就是切换“一级目的地”
                                        navController.navigate(item.route) {
                                            launchSingleTop = true
                                            restoreState = true

                                            // ✅ 关键：回到起点并保留状态（避免堆栈越堆越多）
                                            popUpTo(navController.graph.startDestinationId) {
                                                saveState = true
                                            }
                                        }
                                    },
                                    icon = {
                                        Surface(
                                            modifier = Modifier.size(48.dp),
                                            shape = CircleShape,
                                            color = if (selected) {
                                                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.78f)
                                            } else {
                                                Color.Transparent
                                            }
                                        ) {
                                            Column(
                                                modifier = Modifier.fillMaxSize(),
                                                horizontalAlignment = Alignment.CenterHorizontally,
                                                verticalArrangement = Arrangement.Center
                                            ) {
                                                if (iconResId != 0) {
                                                    Icon(
                                                        painter = painterResource(id = iconResId),
                                                        contentDescription = item.label,
                                                        modifier = Modifier.size(19.dp)
                                                    )
                                                } else {
                                                    Box(Modifier.size(19.dp))
                                                }
                                                Text(
                                                    text = item.label,
                                                    fontSize = 10.sp,
                                                    lineHeight = 10.sp
                                                )
                                            }
                                        }
                                    },
                                    label = null,
                                    alwaysShowLabel = false,
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = MaterialTheme.colorScheme.primary,
                                        selectedTextColor = MaterialTheme.colorScheme.primary,
                                        indicatorColor = Color.Transparent,
                                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                )
                            }
                        }
                    }
                }
            }
            }
        }
    }
    }
}
}

@Composable
private fun GlobalBackgroundImage(
    imagePath: String,
    alpha: Float
) {
    var imageBitmap by remember(imagePath) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(imagePath) {
        imageBitmap = withContext(Dispatchers.IO) {
            BitmapFactory.decodeFile(imagePath)?.asImageBitmap()
        }
    }

    imageBitmap?.let { bitmap ->
        Image(
            bitmap = bitmap,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            alpha = alpha
        )
    }
}

@Composable
private fun PlaybackBottomBar(
    state: HomePlaybackBarState,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
        tonalElevation = 2.dp,
        shadowElevation = 10.dp,
        border = BorderStroke(
            width = 0.8.dp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = state.label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Slider(
                value = state.progress,
                onValueChange = state.onSeekToProgress,
                modifier = Modifier.fillMaxWidth(),
                valueRange = 0f..1f
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = state.onReplay) {
                    Icon(Icons.Default.Replay, contentDescription = stringResource(R.string.playback_replay))
                }
                TextButton(onClick = { state.onSeekBySeconds(-1) }) { Text("-1s") }
                IconButton(onClick = state.onTogglePause) {
                    Icon(
                        imageVector = if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (state.isPlaying) {
                            stringResource(R.string.playback_pause)
                        } else {
                            stringResource(R.string.playback_resume)
                        }
                    )
                }
                TextButton(onClick = { state.onSeekBySeconds(1) }) { Text("+1s") }
                IconButton(onClick = state.onExit) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.playback_exit))
                }
            }
        }
    }
}

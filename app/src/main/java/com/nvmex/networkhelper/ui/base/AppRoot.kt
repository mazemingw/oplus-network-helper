package com.nvmex.networkhelper.ui.base


import android.graphics.BitmapFactory
import android.net.Uri
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.nvmex.networkhelper.util.qmi.QmiQuickLockRequest
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
import androidx.compose.runtime.collectAsState
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
import com.nvmex.networkhelper.ui.menu.QmiBandLockScreen
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
import com.nvmex.networkhelper.ui.wifi.WifiSettingsScreen
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
    val sharedNetworkVm: com.nvmex.networkhelper.viewmodel.signal.NetworkPanelMultiSimViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    // Capture the activity owner without creating the EFS session. Only the two opt-in routes instantiate it.
    val efsSessionOwner = androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner.current!!
    var modemDataSlot by remember { mutableStateOf<Int?>(null) }
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
        val iconResId: Int
    )

    val bottomItems = listOf(
        BottomItem(Routes.HOME, stringResource(R.string.nav_home), R.drawable.home),
        BottomItem(Routes.LOCK, stringResource(R.string.nav_lock), R.drawable.nav_lock),
        BottomItem(Routes.WIFI, stringResource(R.string.nav_wifi), R.drawable.wifi),
        BottomItem(Routes.MENU, stringResource(R.string.nav_menu), R.drawable.menu),
        BottomItem(Routes.SETTINGS, stringResource(R.string.nav_settings), R.drawable.settings),
    )

    // ✅ 当前 route（用 Compose 官方姿势，而不是自己 collect Flow）
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: Routes.HOME

    val showBottomBar = bottomItems.any { it.route == currentRoute }
    val bottomPageInset = 96.dp
    val bottomBarHeight = 64.dp
    val bottomBarGap = 14.dp

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
                    networkVm = sharedNetworkVm,
                    onOpenModemFeatures = { slot -> modemDataSlot = slot; navController.navigate(Routes.MODEM_FEATURES) { launchSingleTop = true } },
                    onQuickLock = { request ->
                        navController.navigate("quick_band_lock/${Uri.encode(request.json())}") { launchSingleTop = true }
                    },
                    targetPkg = targetPkg,
                    targetActivity = targetActivity,
                    vendor = vendor,
                    onPanelState = { sharedPanelState = it },
                    contentBottomPadding = bottomPageInset,
                    onOpenEngineerBandLock = { sim ->
                        if (appSettings.legacyBandLock) showEngineerBandLockDialog = true
                        else navController.navigate("${Routes.ENGINEER_BAND_LOCK}?sim=${sim ?: 0}") { launchSingleTop = true }
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
                Routes.LOCK,
                enterTransition = { slideInHorizontally(initialOffsetX = { it }) + fadeIn() },
                exitTransition = { slideOutHorizontally(targetOffsetX = { -it }) + fadeOut() }
            ) {
                QmiBandLockScreen(
                    onBack = { navController.popBackStack() },
                    networkVm = sharedNetworkVm,
                    contentBottomPadding = bottomBarHeight + bottomBarGap
                )
            }

            composable(
                Routes.RADAR,
                enterTransition = { slideInHorizontally(initialOffsetX = { it }) + fadeIn() },
                exitTransition = { slideOutHorizontally(targetOffsetX = { -it }) + fadeOut() }
            ) {
                SignalScanScreen(onBack = { navController.popBackStack() })
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

            composable(Routes.DIAGNOSTICS) {
                com.nvmex.networkhelper.ui.settings.DiagnosticConsoleScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.EFS_EXPLORER) {
                val sharedEfsVm: dev.qcom.efs.MainViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
                    viewModelStoreOwner = efsSessionOwner, key = "network-helper-efs")
                sharedEfsVm.configureCloud(com.nvmex.networkhelper.network.base.NetworkConfig.BASE_URL + "networkhelp/efs-backups/")
                dev.qcom.efs.EfsExplorerScreen(sharedEfsVm, onBack = { navController.popBackStack() },
                    onFeatures = { navController.navigate(Routes.MODEM_FEATURES) { launchSingleTop = true } })
            }
            composable(Routes.MODEM_FEATURES) {
                val sharedEfsVm: dev.qcom.efs.MainViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
                    viewModelStoreOwner = efsSessionOwner, key = "network-helper-efs")
                sharedEfsVm.configureCloud(com.nvmex.networkhelper.network.base.NetworkConfig.BASE_URL + "networkhelp/efs-backups/")
                val liveDataSubId by sharedNetworkVm.dataSubId.collectAsState()
                val liveSims by sharedNetworkVm.sims.collectAsState()
                val liveDataSlot = liveSims.firstOrNull { it.subscriptionId == liveDataSubId }?.simSlotIndex?.takeIf { it in 0..1 }
                val dataSlot = if (liveSims.isEmpty() && android.telephony.SubscriptionManager.isValidSubscriptionId(liveDataSubId)) modemDataSlot else liveDataSlot
                dev.qcom.efs.ModemFeaturesScreen(sharedEfsVm, onBack = { navController.popBackStack() },
                    defaultDataSlot = dataSlot,
                    defaultDataIcon = { selected -> com.nvmex.networkhelper.ui.network.DefaultDataSimIcon(selected) })
            }
            composable(Routes.WIFI_SETTINGS) {
                WifiSettingsScreen(onBack = { navController.popBackStack() })
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
                Routes.ENGINEER_BAND_LOCK_ROUTE,
                arguments = listOf(navArgument("sim") {
                    type = NavType.IntType
                    defaultValue = 0
                }),
                enterTransition = { slideInHorizontally(initialOffsetX = { it }) + fadeIn() },
                exitTransition = { slideOutHorizontally(targetOffsetX = { -it }) + fadeOut() }
            ) { entry ->
                if (appSettings.legacyBandLock) {
                    EngineerBandLockScreen(onBack = { navController.popBackStack() })
                } else {
                    QmiBandLockScreen(onBack = { navController.popBackStack() }, networkVm = sharedNetworkVm,
                        initialSim = entry.arguments?.getInt("sim")?.takeIf { it in 1..2 })
                }
            }

            composable(
                Routes.QUICK_BAND_LOCK,
                arguments = listOf(navArgument("target") { type = NavType.StringType })
            ) { entry ->
                val request = remember(entry) { runCatching {
                    QmiQuickLockRequest.parse(entry.arguments?.getString("target").orEmpty())
                }.getOrNull() }
                QmiBandLockScreen(onBack = { navController.popBackStack() }, networkVm = sharedNetworkVm,
                    quickRequest = request)
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
                    onBack = { navController.popBackStack() },
                    contentBottomPadding = bottomPageInset
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
                show = showEngineerBandLockDialog && appSettings.legacyBandLock,
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
                        .padding(bottom = bottomBarGap),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth(0.90f)
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
                            modifier = Modifier.height(bottomBarHeight),
                            containerColor = Color.Transparent,
                            tonalElevation = 0.dp
                        ) {
                            bottomItems.forEach { item ->
                                val selected = currentRoute == item.route

                                NavigationBarItem(
                                    selected = selected,
                                    onClick = {
                                        if (!selected || item.route != Routes.LOCK) {
                                            navController.navigate(item.route) {
                                                launchSingleTop = true
                                                restoreState = item.route != Routes.LOCK

                                                popUpTo(navController.graph.startDestinationId) {
                                                    // A popped lock page must release its modem session.
                                                    saveState = currentRoute != Routes.LOCK
                                                }
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
                                                Icon(
                                                    painter = painterResource(item.iconResId),
                                                    contentDescription = item.label,
                                                    modifier = Modifier.size(19.dp)
                                                )
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

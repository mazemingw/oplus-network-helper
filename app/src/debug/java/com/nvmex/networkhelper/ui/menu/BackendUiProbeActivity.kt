package com.nvmex.networkhelper.ui.menu

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nvmex.networkhelper.viewmodel.menu.ModemDriversViewModel

/** Debug-only layout harness. Opens no modem connection; absent from release APKs. */
class BackendUiProbeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scale = intent.getFloatExtra("density", 1f)
        setContent {
            val current = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(current.density * scale, current.fontScale)) {
                MaterialTheme {
                    Surface {
                        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(16.dp)) {
                            Text("频段与小区控制", style = MaterialTheme.typography.headlineSmall)
                            TabRow(selectedTabIndex = 2) {
                                listOf("频段与制式", "小区锁定", "驱动&设置").forEachIndexed { index, text ->
                                    Tab(selected = index == 2, onClick = {}, text = { Text(text) })
                                }
                            }
                            Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                ModemDriverSettings(viewModel<ModemDriversViewModel>(), true, {}, {})
                            }
                        }
                    }
                }
            }
        }
    }
}

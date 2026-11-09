package com.nvmex.networkhelper.ui.wifi

import android.content.Intent
import android.content.SharedPreferences
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.nvmex.networkhelper.R
import com.nvmex.networkhelper.util.wifi.WifiCutoffService
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun WifiSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember(context) { WifiCutoffService.settings(context) }
    val scope = rememberCoroutineScope()
    var enabled by remember(prefs) { mutableStateOf(prefs.getBoolean(WifiCutoffService.KEY_ENABLED, false)) }
    var threshold by remember(prefs) { mutableStateOf(WifiCutoffService.threshold(context).toFloat()) }
    var checkingAccess by remember { mutableStateOf(false) }
    var permissionMissing by remember { mutableStateOf(false) }
    val serviceError by WifiCutoffService.errorMessage.collectAsState()
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == WifiCutoffService.KEY_ENABLED) enabled = prefs.getBoolean(key, false)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.wifi_settings_title), Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
        }
        val title = stringResource(R.string.wifi_cutoff_title)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Switch(
                checked = enabled,
                enabled = !checkingAccess,
                modifier = Modifier.semantics { contentDescription = title },
                onCheckedChange = { turnOn ->
                    permissionMissing = false
                    if (turnOn) {
                        scope.launch {
                            checkingAccess = true
                            try {
                                if (WifiCutoffService.canDisconnectWifi(context)) {
                                    prefs.edit().putBoolean(WifiCutoffService.KEY_ENABLED, true).apply()
                                    WifiCutoffService.startIfEnabled(context)
                                } else permissionMissing = true
                            } finally { checkingAccess = false }
                        }
                    } else {
                        prefs.edit().putBoolean(WifiCutoffService.KEY_ENABLED, false).apply()
                        context.stopService(Intent(context, WifiCutoffService::class.java))
                    }
                }
            )
        }
        Text(stringResource(R.string.wifi_cutoff_description, WifiCutoffService.CHECK_SECONDS,
            WifiCutoffService.RECONNECT_COOLDOWN_SECONDS), style = MaterialTheme.typography.bodyMedium)
        val error = if (permissionMissing) R.string.wifi_cutoff_permission else serviceError
        error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
        val thresholdLabel = stringResource(R.string.wifi_cutoff_threshold, threshold.roundToInt())
        Text(thresholdLabel, style = MaterialTheme.typography.titleMedium)
        Slider(
            value = threshold,
            onValueChange = { threshold = it },
            onValueChangeFinished = {
                threshold = threshold.roundToInt().toFloat()
                prefs.edit().putInt(WifiCutoffService.KEY_THRESHOLD, threshold.toInt()).apply()
            },
            valueRange = WifiCutoffService.MIN_THRESHOLD.toFloat()..WifiCutoffService.MAX_THRESHOLD.toFloat(),
            modifier = Modifier.semantics { contentDescription = thresholdLabel }
        )
    }
}

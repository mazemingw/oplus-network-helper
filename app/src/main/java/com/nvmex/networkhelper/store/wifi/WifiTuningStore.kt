package com.nvmex.networkhelper.store.wifi

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.wifiTuningDataStore by preferencesDataStore(name = "wifi_tuning")

data class WifiTuningState(
    val hiPerfEnabled: Boolean = false,
    val lowLatencyEnabled: Boolean = false
)

class WifiTuningStore(private val context: Context) {

    private object Keys {
        val HI_PERF = booleanPreferencesKey("wifi_hi_perf_enabled")
        val LOW_LAT = booleanPreferencesKey("wifi_low_latency_enabled")
    }

    val stateFlow: Flow<WifiTuningState> =
        context.wifiTuningDataStore.data.map { p: Preferences ->
            WifiTuningState(
                hiPerfEnabled = p[Keys.HI_PERF] ?: false,
                lowLatencyEnabled = p[Keys.LOW_LAT] ?: false
            )
        }

    suspend fun setHiPerf(enabled: Boolean) {
        context.wifiTuningDataStore.edit { it[Keys.HI_PERF] = enabled }
    }

    suspend fun setLowLatency(enabled: Boolean) {
        context.wifiTuningDataStore.edit { it[Keys.LOW_LAT] = enabled }
    }
}

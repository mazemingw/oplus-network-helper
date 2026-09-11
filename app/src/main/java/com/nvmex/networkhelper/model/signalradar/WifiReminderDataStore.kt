package com.nvmex.networkhelper.model.signalradar

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "wifi_reminder")

class WifiReminderDataStore(private val context: Context) {
    companion object {
        private val REMINDER_DISABLED_KEY = booleanPreferencesKey("wifi_reminder_disabled")
    }

    // 是否已禁用提醒
    suspend fun setReminderDisabled(disabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[REMINDER_DISABLED_KEY] = disabled
        }
    }

    // 获取提醒状态
    fun isReminderDisabledFlow(): Flow<Boolean> =
        context.dataStore.data.map { preferences ->
            preferences[REMINDER_DISABLED_KEY] ?: false
        }
}
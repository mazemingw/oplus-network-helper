package com.nvmex.networkhelper.ui.settings.fonts

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

// 扩展 Context 的 DataStore
private val Context.dataStore by preferencesDataStore(name = "font_settings")

class FontSizePreferences(private val context: Context) {
    companion object {
        private val FONT_SIZE_KEY = stringPreferencesKey("font_size_level")
    }

    // 保存字体设置
    suspend fun saveFontSizeLevel(level: FontSizeConfig.FontSizeLevel) {
        context.dataStore.edit { preferences ->
            preferences[FONT_SIZE_KEY] = level.name
        }
    }

    // 读取字体设置
    fun getFontSizeLevelFlow(): Flow<FontSizeConfig.FontSizeLevel> =
        context.dataStore.data.map { preferences ->
            val levelName = preferences[FONT_SIZE_KEY] ?: FontSizeConfig.FontSizeLevel.MEDIUM.name
            FontSizeConfig.FontSizeLevel.valueOf(levelName)
        }

    // 同步读取（用于应用启动时）
    suspend fun getSavedFontSizeLevel(): FontSizeConfig.FontSizeLevel {
        val preferences = context.dataStore.data.first()
        val levelName = preferences[FONT_SIZE_KEY] ?: FontSizeConfig.FontSizeLevel.MEDIUM.name
        return FontSizeConfig.FontSizeLevel.valueOf(levelName)
    }
}
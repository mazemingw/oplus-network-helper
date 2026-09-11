package com.nvmex.networkhelper.ui.settings.fonts

import android.content.Context
import androidx.compose.runtime.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

object FontSizeConfig {
    enum class FontSizeLevel(
        val scale: Float
    ) {
        EXTRA_SMALL(0.6f),
        SMALL(0.8f),
        MEDIUM(1.0f),
        LARGE(1.2f),
        EXTRA_LARGE(1.4f),
        HUGE(1.6f)
    }

    private val _currentLevel = MutableStateFlow(FontSizeLevel.MEDIUM)
    val currentLevel: StateFlow<FontSizeLevel> = _currentLevel.asStateFlow()

    // 延迟初始化 preferences
    private var preferences: FontSizePreferences? = null

    // 初始化方法（在 Application 中调用）
    fun init(context: Context) {
        preferences = FontSizePreferences(context)

        // 异步加载保存的设置
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val savedLevel = preferences?.getSavedFontSizeLevel()
                savedLevel?.let {
                    _currentLevel.value = it
                }
            } catch (e: Exception) {
                // 出错时使用默认值
                _currentLevel.value = FontSizeLevel.MEDIUM
            }
        }
    }

    fun setLevel(level: FontSizeLevel) {
        _currentLevel.value = level

        // 异步保存到 DataStore
        CoroutineScope(Dispatchers.IO).launch {
            preferences?.saveFontSizeLevel(level)
        }
    }
}

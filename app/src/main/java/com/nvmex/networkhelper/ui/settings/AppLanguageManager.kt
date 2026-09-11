package com.nvmex.networkhelper.ui.settings

import android.app.LocaleManager
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.core.content.edit
import java.util.Locale

object AppLanguageManager {
    const val TAG_SYSTEM = "system"
    const val TAG_ZH_CN = "zh-CN"
    const val TAG_EN = "en"

    private const val SP_NAME = "nh_settings"
    private const val KEY_LANGUAGE_TAG = "language_tag"

    fun getLanguageTag(context: Context): String {
        return context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LANGUAGE_TAG, TAG_SYSTEM)
            .takeUnless { it.isNullOrBlank() }
            ?: TAG_SYSTEM
    }

    fun setLanguageTag(context: Context, tag: String) {
        val normalized = normalizeTag(tag)
        context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE).edit {
            putString(KEY_LANGUAGE_TAG, normalized)
        }

        if (Build.VERSION.SDK_INT >= 33) {
            val localeManager = context.getSystemService(LocaleManager::class.java)
            localeManager?.applicationLocales = if (normalized == TAG_SYSTEM) {
                LocaleList.getEmptyLocaleList()
            } else {
                LocaleList.forLanguageTags(normalized)
            }
        }
    }

    fun wrap(base: Context): Context {
        val tag = getLanguageTag(base)
        if (tag == TAG_SYSTEM) return base

        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)

        val config = Configuration(base.resources.configuration)
        if (Build.VERSION.SDK_INT >= 24) {
            config.setLocales(LocaleList(locale))
        } else {
            @Suppress("DEPRECATION")
            config.setLocale(locale)
        }
        return ContextWrapper(base.createConfigurationContext(config))
    }

    private fun normalizeTag(tag: String): String {
        return when (tag) {
            TAG_ZH_CN, TAG_EN -> tag
            else -> TAG_SYSTEM
        }
    }
}

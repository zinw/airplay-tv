package com.flymop.airplaytv

import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/** Persists and applies in-app UI locale (English / Simplified Chinese). */
object LocaleHelper {
    const val LANG_EN = "en"
    const val LANG_ZH_CN = "zh-CN"

    fun getLanguage(prefs: SharedPreferences): String {
        return prefs.getString(Prefs.LANGUAGE, Prefs.DEF_LANGUAGE) ?: Prefs.DEF_LANGUAGE
    }

    fun displayNameRes(languageTag: String): Int = when (languageTag) {
        LANG_ZH_CN -> R.string.language_zh_cn
        else -> R.string.language_english
    }

    fun cycleLanguage(prefs: SharedPreferences): String {
        val next = when (getLanguage(prefs)) {
            LANG_ZH_CN -> LANG_EN
            else -> LANG_ZH_CN
        }
        applyLanguage(prefs, next)
        return next
    }

    fun applyStoredLanguage(prefs: SharedPreferences) {
        applyLanguage(prefs, getLanguage(prefs))
    }

    fun applyLanguage(prefs: SharedPreferences, languageTag: String) {
        prefs.edit().putString(Prefs.LANGUAGE, languageTag).apply()
        val locales = LocaleListCompat.forLanguageTags(languageTag)
        if (AppCompatDelegate.getApplicationLocales() != locales) {
            AppCompatDelegate.setApplicationLocales(locales)
        }
    }
}

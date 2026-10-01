package com.flymop.airplaytv

import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/** Persists and applies in-app UI locale (system / English / Simplified Chinese). */
object LocaleHelper {
    const val LANG_SYSTEM = "system"
    const val LANG_EN = "en"
    const val LANG_ZH_CN = "zh-CN"

    private val CYCLE = listOf(LANG_SYSTEM, LANG_ZH_CN, LANG_EN)

    fun getLanguage(prefs: SharedPreferences): String {
        val raw = prefs.getString(Prefs.LANGUAGE, Prefs.DEF_LANGUAGE) ?: Prefs.DEF_LANGUAGE
        return normalize(raw)
    }

    fun displayNameRes(languageTag: String): Int = when (normalize(languageTag)) {
        LANG_ZH_CN -> R.string.language_zh_cn
        LANG_SYSTEM -> R.string.language_system
        else -> R.string.language_english
    }

    fun cycleLanguage(prefs: SharedPreferences): String {
        val current = getLanguage(prefs)
        val idx = CYCLE.indexOf(current).let { if (it < 0) 0 else it }
        val next = CYCLE[(idx + 1) % CYCLE.size]
        applyLanguage(prefs, next)
        return next
    }

    fun applyStoredLanguage(prefs: SharedPreferences) {
        applyLanguage(prefs, getLanguage(prefs))
    }

    fun applyLanguage(prefs: SharedPreferences, languageTag: String) {
        val normalized = normalize(languageTag)
        prefs.edit().putString(Prefs.LANGUAGE, normalized).apply()
        val locales = if (normalized == LANG_SYSTEM) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(normalized)
        }
        if (AppCompatDelegate.getApplicationLocales() != locales) {
            AppCompatDelegate.setApplicationLocales(locales)
        }
    }

    private fun normalize(languageTag: String): String = when (languageTag.trim()) {
        "", LANG_SYSTEM -> LANG_SYSTEM
        LANG_ZH_CN, "zh", "zh_CN", "zh-Hans", "zh-Hans-CN" -> LANG_ZH_CN
        LANG_EN, "en-US", "en-GB" -> LANG_EN
        else -> languageTag.trim().ifBlank { LANG_SYSTEM }
    }
}

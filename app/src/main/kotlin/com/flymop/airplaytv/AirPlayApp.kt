package com.flymop.airplaytv

import android.app.Application

class AirPlayApp : Application() {
    override fun onCreate() {
        super.onCreate()
        LocaleHelper.applyStoredLanguage(getSharedPreferences(Prefs.NAME, MODE_PRIVATE))
    }
}

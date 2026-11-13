package com.manajir.voicetest

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Lang.init(this)
    }
}

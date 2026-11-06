package com.zzu.lite

import android.app.Application
import com.zzu.lite.di.AppContainer

class ZzuApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

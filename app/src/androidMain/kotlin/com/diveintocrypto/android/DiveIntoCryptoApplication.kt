package com.diveintocrypto.android

import android.app.Application
import com.diveintocrypto.android.data.createAppContainer

class DiveIntoCryptoApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = createAppContainer(this)
    }
}

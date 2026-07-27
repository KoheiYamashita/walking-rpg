package com.walkingrpg.app

import android.app.Application
import com.walkingrpg.app.di.initKoin
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger

class WalkingRpgApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        initKoin {
            androidLogger()
            androidContext(this@WalkingRpgApplication)
        }
    }
}

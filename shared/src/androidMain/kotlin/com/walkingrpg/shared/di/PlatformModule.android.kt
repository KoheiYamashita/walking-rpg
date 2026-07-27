package com.walkingrpg.shared.di

import com.walkingrpg.shared.platform.map.AndroidMapCameraSource
import com.walkingrpg.shared.platform.map.MapCameraSource
import org.koin.core.module.Module
import org.koin.dsl.module

actual val platformModule: Module = module {
    single<MapCameraSource> { AndroidMapCameraSource() }
}

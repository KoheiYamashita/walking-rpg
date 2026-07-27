package com.walkingrpg.shared.di

import com.walkingrpg.shared.platform.map.IosLocalMapSource
import com.walkingrpg.shared.platform.map.LocalMapSource
import org.koin.core.module.Module
import org.koin.dsl.module

actual val platformModule: Module = module {
    single<LocalMapSource> { IosLocalMapSource() }
}

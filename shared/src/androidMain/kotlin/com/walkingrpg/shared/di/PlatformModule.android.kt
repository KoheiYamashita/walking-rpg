package com.walkingrpg.shared.di

import android.content.Context
import com.walkingrpg.shared.platform.map.AndroidLocalMapSource
import com.walkingrpg.shared.platform.map.LocalMapSource
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * `Context` はエントリポイント（`WalkingRpgApplication`）が
 * `androidContext(...)` で登録したものを `get()` で解決する。
 */
actual val platformModule: Module = module {
    single<LocalMapSource> { AndroidLocalMapSource(get<Context>()) }
}

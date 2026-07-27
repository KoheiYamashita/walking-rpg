package com.walkingrpg.shared.di

import com.walkingrpg.shared.platform.Platform
import com.walkingrpg.shared.platform.currentPlatform
import org.koin.dsl.module

/**
 * shared モジュールのDI定義。
 * UseCase（domain）・Repository実装（data）・expect/actual実装（platform）を
 * ここで束ねる。雛形では [Platform] のみ。
 */
val sharedModule = module {
    single<Platform> { currentPlatform() }
}

package com.walkingrpg.app.di

import com.walkingrpg.app.AppViewModel
import com.walkingrpg.app.ui.codex.CodexViewModel
import com.walkingrpg.app.ui.home.HomeViewModel
import com.walkingrpg.app.ui.map.MapViewModel
import com.walkingrpg.app.ui.setup.SetupViewModel
import com.walkingrpg.shared.di.sharedModule
import org.koin.core.KoinApplication
import org.koin.core.context.startKoin
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.KoinAppDeclaration
import org.koin.dsl.module

/**
 * UI層（ViewModel）のDI定義。
 * ViewModelのコンストラクタ引数（UseCase）は `sharedModule` から解決される。
 */
val appModule = module {
    viewModelOf(::AppViewModel)
    viewModelOf(::HomeViewModel)
    viewModelOf(::MapViewModel)
    viewModelOf(::CodexViewModel)
    viewModelOf(::SetupViewModel)
}

/** Android / iOS 双方のエントリポイントから呼ぶKoin初期化。 */
fun initKoin(appDeclaration: KoinAppDeclaration = {}): KoinApplication =
    startKoin {
        appDeclaration()
        modules(sharedModule, appModule)
    }

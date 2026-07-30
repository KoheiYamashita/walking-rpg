package com.walkingrpg.app.di

import com.walkingrpg.app.AppViewModel
import com.walkingrpg.app.ui.album.AlbumViewModel
import com.walkingrpg.app.ui.codex.CodexViewModel
import com.walkingrpg.app.ui.home.HomeViewModel
import com.walkingrpg.app.ui.map.MapViewModel
import com.walkingrpg.app.ui.review.ReviewViewModel
import com.walkingrpg.app.ui.settings.SettingsViewModel
import com.walkingrpg.app.ui.setup.SetupViewModel
import com.walkingrpg.app.ui.snapshot.ComposeMonthlySnapshotRenderer
import com.walkingrpg.shared.di.sharedModule
import com.walkingrpg.shared.domain.snapshot.MonthlySnapshotRenderer
import org.koin.core.KoinApplication
import org.koin.core.context.startKoin
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.KoinAppDeclaration
import org.koin.dsl.module

/**
 * UI層（ViewModel）のDI定義。
 * ViewModelのコンストラクタ引数（UseCase）は `sharedModule` から解決される。
 *
 * ViewModel以外に1つだけ、**ドメインのポートの実装**が入っている
 * （[MonthlySnapshotRenderer]）。描画APIがCompose側にしか無いためで、
 * 理由と例外である旨はそのインターフェースのKDocにある。
 */
val appModule = module {
    viewModelOf(::AppViewModel)
    viewModelOf(::AlbumViewModel)
    viewModelOf(::HomeViewModel)
    viewModelOf(::MapViewModel)
    viewModelOf(::CodexViewModel)
    viewModelOf(::ReviewViewModel)
    viewModelOf(::SetupViewModel)
    viewModelOf(::SettingsViewModel)

    // --- 月次スナップショット（issue #17） ---
    // sharedのポートをUI層が実装する唯一の例（MonthlySnapshotRenderer のKDoc）。
    // 生成の入口（GenerateMonthlySnapshotsUseCase）は sharedModule 側にあり、
    // これを get() で受ける＝ドメインは composeApp を名指ししない。
    single<MonthlySnapshotRenderer> { ComposeMonthlySnapshotRenderer() }
}

/** Android / iOS 双方のエントリポイントから呼ぶKoin初期化。 */
fun initKoin(appDeclaration: KoinAppDeclaration = {}): KoinApplication =
    startKoin {
        appDeclaration()
        modules(sharedModule, appModule)
    }

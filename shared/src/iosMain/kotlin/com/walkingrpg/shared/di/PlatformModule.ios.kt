package com.walkingrpg.shared.di

import com.walkingrpg.shared.platform.DatabaseDriverFactory
import com.walkingrpg.shared.platform.FileShare
import com.walkingrpg.shared.platform.IosDatabaseDriverFactory
import com.walkingrpg.shared.platform.IosFileShare
import com.walkingrpg.shared.platform.IosLocationPermissionController
import com.walkingrpg.shared.platform.IosLocationProvider
import com.walkingrpg.shared.platform.IosSessionKeeper
import com.walkingrpg.shared.platform.LocationPermissionController
import com.walkingrpg.shared.platform.LocationProvider
import com.walkingrpg.shared.platform.SessionKeeper
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * iOSのプラットフォーム実装。DBドライバ以外は #3 まではスタブ。
 * ビルドと配線だけ通しておき、実装の穴は実行時に明示的なエラーで分かるようにする。
 */
actual val platformModule: Module = module {
    // --- 散歩セッション（issue #2） ---
    single<LocationPermissionController> { IosLocationPermissionController() }
    single<LocationProvider> { IosLocationProvider() }
    single<SessionKeeper> { IosSessionKeeper() }
    single<FileShare> { IosFileShare() }
    single<DatabaseDriverFactory> { IosDatabaseDriverFactory() }
}

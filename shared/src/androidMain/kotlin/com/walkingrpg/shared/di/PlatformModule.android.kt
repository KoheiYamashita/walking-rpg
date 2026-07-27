package com.walkingrpg.shared.di

import com.walkingrpg.shared.platform.AndroidAppSettings
import com.walkingrpg.shared.platform.AndroidDatabaseDriverFactory
import com.walkingrpg.shared.platform.AndroidFileShare
import com.walkingrpg.shared.platform.AndroidLocationPermissionController
import com.walkingrpg.shared.platform.AndroidLocationProvider
import com.walkingrpg.shared.platform.AndroidSecureStorage
import com.walkingrpg.shared.platform.AndroidSessionKeeper
import com.walkingrpg.shared.platform.AppSettings
import com.walkingrpg.shared.platform.DatabaseDriverFactory
import com.walkingrpg.shared.platform.FileShare
import com.walkingrpg.shared.platform.LocationPermissionController
import com.walkingrpg.shared.platform.LocationProvider
import com.walkingrpg.shared.platform.SecureStorage
import com.walkingrpg.shared.platform.SessionKeeper
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.bind
import org.koin.dsl.module

/**
 * Androidのプラットフォーム実装。
 *
 * [AndroidLocationPermissionController] だけは具象型でも解決できるようにしてある
 * （エントリポイントの `MainActivity` が `attach` でランチャーを預けるため）。
 */
actual val platformModule: Module = module {
    // --- 散歩セッション（issue #2） ---
    single { AndroidLocationPermissionController(androidContext()) } bind
        LocationPermissionController::class
    single<LocationProvider> { AndroidLocationProvider(androidContext()) }
    single<SessionKeeper> { AndroidSessionKeeper(androidContext()) }
    single<FileShare> { AndroidFileShare(androidContext()) }
    single<DatabaseDriverFactory> { AndroidDatabaseDriverFactory(androidContext()) }

    // --- 設定・キー保管（issue #6） ---
    single<SecureStorage> { AndroidSecureStorage(androidContext()) }
    single<AppSettings> { AndroidAppSettings(androidContext()) }
}

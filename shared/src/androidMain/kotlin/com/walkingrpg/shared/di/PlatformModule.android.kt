package com.walkingrpg.shared.di

import com.walkingrpg.shared.platform.AndroidAppSettings
import com.walkingrpg.shared.platform.AndroidBackupArchive
import com.walkingrpg.shared.platform.AndroidDatabaseDriverFactory
import com.walkingrpg.shared.platform.AndroidFilePicker
import com.walkingrpg.shared.platform.AndroidFileShare
import com.walkingrpg.shared.platform.AndroidLocationPermissionController
import com.walkingrpg.shared.platform.AndroidLocationProvider
import com.walkingrpg.shared.platform.AndroidNetworkStatus
import com.walkingrpg.shared.platform.AndroidSecureStorage
import com.walkingrpg.shared.platform.AndroidSessionKeeper
import com.walkingrpg.shared.platform.AndroidSnapshotImageStore
import com.walkingrpg.shared.platform.AndroidWalkNotifier
import com.walkingrpg.shared.platform.AppSettings
import com.walkingrpg.shared.platform.BackupArchive
import com.walkingrpg.shared.platform.AndroidHaptics
import com.walkingrpg.shared.platform.Haptics
import com.walkingrpg.shared.domain.llm.NetworkStatus
import com.walkingrpg.shared.domain.steps.DailyStepsSource
import com.walkingrpg.shared.platform.DatabaseDriverFactory
import com.walkingrpg.shared.platform.FilePicker
import com.walkingrpg.shared.platform.FileShare
import com.walkingrpg.shared.platform.HealthConnectDailyStepsSource
import com.walkingrpg.shared.platform.LocationPermissionController
import com.walkingrpg.shared.platform.LocationProvider
import com.walkingrpg.shared.platform.SecureStorage
import com.walkingrpg.shared.platform.SessionKeeper
import com.walkingrpg.shared.platform.SnapshotImageStore
import com.walkingrpg.shared.platform.WalkNotifier
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
    single<WalkNotifier> { AndroidWalkNotifier(androidContext()) }
    single<FileShare> { AndroidFileShare(androidContext()) }
    single<DatabaseDriverFactory> { AndroidDatabaseDriverFactory(androidContext()) }

    // --- 歩行中フィードバック（issue #12） ---
    single<Haptics> { AndroidHaptics(androidContext()) }

    // --- 押し忘れ救済（issue #7） ---
    single<DailyStepsSource> { HealthConnectDailyStepsSource(androidContext()) }

    // --- LLM生成基盤（issue #14） ---
    single<NetworkStatus> { AndroidNetworkStatus(androidContext()) }

    // --- 月次スナップショット（issue #17） ---
    // 画像は filesDir 配下（Auto Backup の既定対象）。cacheDir には置かない
    single<SnapshotImageStore> { AndroidSnapshotImageStore(androidContext()) }

    // --- 設定・キー保管（issue #6） ---
    single<SecureStorage> { AndroidSecureStorage(androidContext()) }
    single<AppSettings> { AndroidAppSettings(androidContext()) }

    // --- バックアップ（issue #18） ---
    // zipは java.util.zip（依存を足さない）。ファイル選択は MainActivity が
    // attach でランチャーを預けるので、権限コントローラと同じく具象型でも解決できるようにする
    single<BackupArchive> { AndroidBackupArchive() }
    single { AndroidFilePicker(androidContext()) } bind FilePicker::class
}

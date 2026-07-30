package com.walkingrpg.shared.di

import com.walkingrpg.shared.domain.llm.NetworkStatus
import com.walkingrpg.shared.domain.steps.DailyStepsSource
import com.walkingrpg.shared.platform.AppSettings
import com.walkingrpg.shared.platform.BackupArchive
import com.walkingrpg.shared.platform.DatabaseDriverFactory
import com.walkingrpg.shared.platform.FilePicker
import com.walkingrpg.shared.platform.FileShare
import com.walkingrpg.shared.platform.Haptics
import com.walkingrpg.shared.platform.IosAppSettings
import com.walkingrpg.shared.platform.IosBackupArchive
import com.walkingrpg.shared.platform.IosDailyStepsSource
import com.walkingrpg.shared.platform.IosDatabaseDriverFactory
import com.walkingrpg.shared.platform.IosHaptics
import com.walkingrpg.shared.platform.IosFilePicker
import com.walkingrpg.shared.platform.IosFileShare
import com.walkingrpg.shared.platform.IosLocationPermissionController
import com.walkingrpg.shared.platform.IosLocationProvider
import com.walkingrpg.shared.platform.IosNetworkStatus
import com.walkingrpg.shared.platform.IosSecureStorage
import com.walkingrpg.shared.platform.IosSessionKeeper
import com.walkingrpg.shared.platform.IosSnapshotImageStore
import com.walkingrpg.shared.platform.IosWalkNotifier
import com.walkingrpg.shared.platform.LocationPermissionController
import com.walkingrpg.shared.platform.LocationProvider
import com.walkingrpg.shared.platform.SecureStorage
import com.walkingrpg.shared.platform.SessionKeeper
import com.walkingrpg.shared.platform.SnapshotImageStore
import com.walkingrpg.shared.platform.WalkNotifier
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
    single<WalkNotifier> { IosWalkNotifier() }
    single<FileShare> { IosFileShare() }
    single<DatabaseDriverFactory> { IosDatabaseDriverFactory() }

    // --- 歩行中フィードバック（issue #12）。実装は #3 でCore Hapticsに差し替える ---
    single<Haptics> { IosHaptics() }

    // --- 押し忘れ救済（issue #7） ---
    // HealthKit は後続issue。今は常に「取れなかった」を返す
    single<DailyStepsSource> { IosDailyStepsSource() }

    // --- LLM生成基盤（issue #14）。実装は #3 で NWPathMonitor に差し替える ---
    single<NetworkStatus> { IosNetworkStatus() }

    // --- 月次スナップショット（issue #17） ---
    // 画像は NSDocumentDirectory 配下（iCloudバックアップの既定対象）
    single<SnapshotImageStore> { IosSnapshotImageStore() }

    // --- 設定・キー保管（issue #6） ---
    single<SecureStorage> { IosSecureStorage() }
    single<AppSettings> { IosAppSettings() }

    // --- バックアップ（issue #18）。実装は #3 でzipとDocument Pickerに差し替える ---
    single<BackupArchive> { IosBackupArchive() }
    single<FilePicker> { IosFilePicker() }
}

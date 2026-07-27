package com.walkingrpg.shared.di

import com.walkingrpg.shared.data.CurrentLocationRepositoryImpl
import com.walkingrpg.shared.data.LocationPermissionRepositoryImpl
import com.walkingrpg.shared.data.SystemClock
import com.walkingrpg.shared.data.SystemInfoRepositoryImpl
import com.walkingrpg.shared.data.WalkRecorderImpl
import com.walkingrpg.shared.data.WalkSessionExporterImpl
import com.walkingrpg.shared.data.WalkSessionRepositoryImpl
import com.walkingrpg.shared.data.createDatabase
import com.walkingrpg.shared.data.map.MapCameraRepositoryImpl
import com.walkingrpg.shared.domain.Clock
import com.walkingrpg.shared.domain.GetPlatformNameUseCase
import com.walkingrpg.shared.domain.SystemInfoRepository
import com.walkingrpg.shared.domain.map.GetMapSceneUseCase
import com.walkingrpg.shared.domain.map.MapCameraRepository
import com.walkingrpg.shared.domain.walk.CurrentLocationRepository
import com.walkingrpg.shared.domain.walk.ExportWalkSessionUseCase
import com.walkingrpg.shared.domain.walk.LocationPermissionRepository
import com.walkingrpg.shared.domain.walk.ObserveIsWalkingUseCase
import com.walkingrpg.shared.domain.walk.ObserveLocationPermissionUseCase
import com.walkingrpg.shared.domain.walk.ObserveWalkRecordingUseCase
import com.walkingrpg.shared.domain.walk.ObserveWalkSessionsUseCase
import com.walkingrpg.shared.domain.walk.RefreshLocationPermissionUseCase
import com.walkingrpg.shared.domain.walk.RequestLocationPermissionUseCase
import com.walkingrpg.shared.domain.walk.StartWalkSessionUseCase
import com.walkingrpg.shared.domain.walk.StopWalkSessionUseCase
import com.walkingrpg.shared.domain.walk.WalkRecorder
import com.walkingrpg.shared.domain.walk.WalkSessionExporter
import com.walkingrpg.shared.domain.walk.WalkSessionRepository
import com.walkingrpg.shared.platform.Platform
import com.walkingrpg.shared.platform.currentPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.module.dsl.factoryOf
import org.koin.core.module.dsl.singleOf
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module

/** アプリ全体で生き続けるコルーチンスコープ（記録の収集を回す場所）。 */
val APP_SCOPE = named("appScope")

/**
 * shared モジュールのDI定義。
 * UseCase（domain）・Repository実装（data）・expect/actual実装（platform）を
 * ここで束ねる。UI層に見せるのはUseCaseだけで、Repository実装と
 * [Platform] はこの層に閉じる。
 */
val sharedModule = module {
    includes(platformModule)

    single<Platform> { currentPlatform() }

    singleOf(::SystemInfoRepositoryImpl) bind SystemInfoRepository::class
    factoryOf(::GetPlatformNameUseCase)

    single<CoroutineScope>(APP_SCOPE) { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    single<Clock> { SystemClock() }

    // --- 散歩セッション（issue #2） ---
    single { createDatabase(get()) }
    single<WalkSessionRepository> { WalkSessionRepositoryImpl(get()) }
    single<LocationPermissionRepository> { LocationPermissionRepositoryImpl(get()) }
    single<WalkSessionExporter> { WalkSessionExporterImpl(get(), get()) }
    single<WalkRecorder> {
        WalkRecorderImpl(
            locationProvider = get(),
            sessionRepository = get(),
            sessionKeeper = get(),
            clock = get(),
            scope = get(APP_SCOPE),
        )
    }

    factoryOf(::StartWalkSessionUseCase)
    factoryOf(::StopWalkSessionUseCase)
    factoryOf(::ObserveWalkRecordingUseCase)
    factoryOf(::ObserveIsWalkingUseCase)
    factoryOf(::ObserveWalkSessionsUseCase)
    factoryOf(::ObserveLocationPermissionUseCase)
    factoryOf(::RequestLocationPermissionUseCase)
    factoryOf(::RefreshLocationPermissionUseCase)
    factoryOf(::ExportWalkSessionUseCase)

    // --- 地図（issue #4） ---
    singleOf(::MapCameraRepositoryImpl) bind MapCameraRepository::class
    // 現在地は記録用の測位（WalkRecorder）と同じ LocationProvider / 権限判定を使い回す
    single<CurrentLocationRepository> { CurrentLocationRepositoryImpl(get(), get()) }
    factoryOf(::GetMapSceneUseCase)
}

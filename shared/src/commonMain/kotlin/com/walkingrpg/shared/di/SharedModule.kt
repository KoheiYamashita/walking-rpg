package com.walkingrpg.shared.di

import com.walkingrpg.shared.data.CurrentLocationRepositoryImpl
import com.walkingrpg.shared.data.LocationPermissionRepositoryImpl
import com.walkingrpg.shared.data.SystemClock
import com.walkingrpg.shared.data.SystemInfoRepositoryImpl
import com.walkingrpg.shared.data.WalkRecorderImpl
import com.walkingrpg.shared.data.WalkSessionExporterImpl
import com.walkingrpg.shared.data.SetupRepositoryImpl
import com.walkingrpg.shared.data.WalkSessionRepositoryImpl
import com.walkingrpg.shared.data.createDatabase
import com.walkingrpg.shared.data.growth.InMemoryRecentGrowthRepository
import com.walkingrpg.shared.data.growth.WayGrowthRepositoryImpl
import com.walkingrpg.shared.data.llm.HttpLlmConnectionTester
import com.walkingrpg.shared.data.matching.PassageRepositoryImpl
import com.walkingrpg.shared.data.llm.llmHttpClient
import com.walkingrpg.shared.data.osm.OsmMasterRepositoryImpl
import com.walkingrpg.shared.data.osm.OverpassConfig
import com.walkingrpg.shared.data.osm.OverpassOsmAreaSource
import com.walkingrpg.shared.data.osm.osmHttpClient
import com.walkingrpg.shared.data.steps.StepImportRepositoryImpl
import com.walkingrpg.shared.data.steps.SystemCalendarDays
import com.walkingrpg.shared.domain.Clock
import com.walkingrpg.shared.domain.GetPlatformNameUseCase
import com.walkingrpg.shared.domain.SystemInfoRepository
import com.walkingrpg.shared.domain.growth.GrowthConfig
import com.walkingrpg.shared.domain.growth.ObserveGrowthUpdatesUseCase
import com.walkingrpg.shared.domain.growth.RecentGrowthRepository
import com.walkingrpg.shared.domain.growth.RecomputeAfterWalkUseCase
import com.walkingrpg.shared.domain.growth.RecomputeWayGrowthUseCase
import com.walkingrpg.shared.domain.growth.WayGrowthRepository
import com.walkingrpg.shared.domain.map.GetMapSceneUseCase
import com.walkingrpg.shared.domain.matching.MapMatchingConfig
import com.walkingrpg.shared.domain.matching.PassageRepository
import com.walkingrpg.shared.domain.matching.RecomputePassagesUseCase
import com.walkingrpg.shared.domain.osm.GetOsmMasterCountsUseCase
import com.walkingrpg.shared.domain.osm.ImportOsmAreaUseCase
import com.walkingrpg.shared.domain.osm.OsmAreaSource
import com.walkingrpg.shared.domain.osm.OsmMasterRepository
import com.walkingrpg.shared.domain.setup.CompleteSetupUseCase
import com.walkingrpg.shared.domain.setup.IsSetupCompletedUseCase
import com.walkingrpg.shared.domain.setup.LlmConnectionTester
import com.walkingrpg.shared.domain.setup.LoadSetupSettingsUseCase
import com.walkingrpg.shared.domain.setup.RegisterHomeAnchorUseCase
import com.walkingrpg.shared.domain.setup.SaveWeatherSettingsUseCase
import com.walkingrpg.shared.domain.setup.SetupRepository
import com.walkingrpg.shared.domain.setup.TestLlmConnectionUseCase
import com.walkingrpg.shared.domain.setup.UpdateHomeBlurRadiusUseCase
import com.walkingrpg.shared.domain.steps.CalendarDays
import com.walkingrpg.shared.domain.steps.ImportDailyStepsUseCase
import com.walkingrpg.shared.domain.steps.StepImportRepository
import com.walkingrpg.shared.domain.walk.CurrentLocationRepository
import com.walkingrpg.shared.domain.walk.ExportWalkSessionUseCase
import com.walkingrpg.shared.domain.walk.HomeArrivalConfig
import com.walkingrpg.shared.domain.walk.LocationPermissionRepository
import com.walkingrpg.shared.domain.walk.ObserveFinishedWalksUseCase
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
 * OSM取り込み用のHTTPクライアント。
 * LLM・天候の呼び出し（別issue）とは設定（User-Agent・リトライ方針）が違うので分けておく。
 */
private val OSM_HTTP_CLIENT = named("osmHttpClient")

/**
 * LLM疎通テスト用のHTTPクライアント（issue #6）。
 * リトライなし・短いタイムアウトで、OSM取り込み用とは方針が違うので分けてある。
 */
private val LLM_HTTP_CLIENT = named("llmHttpClient")

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
    // 自動終了の閾値（HomeArrivalConfig）はここで差し替えられる。UIからは触らせない。
    single { HomeArrivalConfig.DEFAULT }
    single<WalkRecorder> {
        WalkRecorderImpl(
            locationProvider = get(),
            sessionRepository = get(),
            sessionKeeper = get(),
            setupRepository = get(),
            walkNotifier = get(),
            clock = get(),
            scope = get(APP_SCOPE),
            homeArrivalConfig = get(),
        )
    }

    factoryOf(::StartWalkSessionUseCase)
    factoryOf(::StopWalkSessionUseCase)
    factoryOf(::ObserveWalkRecordingUseCase)
    factoryOf(::ObserveIsWalkingUseCase)
    factoryOf(::ObserveWalkSessionsUseCase)
    factoryOf(::ObserveFinishedWalksUseCase)
    factoryOf(::ObserveLocationPermissionUseCase)
    factoryOf(::RequestLocationPermissionUseCase)
    factoryOf(::RefreshLocationPermissionUseCase)
    factoryOf(::ExportWalkSessionUseCase)

    // --- 地図（issue #4 / #10） ---
    // 現在地は記録用の測位（WalkRecorder）と同じ LocationProvider / 権限判定を使い回す
    single<CurrentLocationRepository> { CurrentLocationRepositoryImpl(get(), get()) }
    factoryOf(::GetMapSceneUseCase)

    // --- OSMマスタの取り込み（issue #5） ---
    // エンドポイント等はここで差し替えられる（OverpassConfig）
    single { OverpassConfig() }
    single(OSM_HTTP_CLIENT) { osmHttpClient(get()) }
    single<OsmAreaSource> { OverpassOsmAreaSource(get(OSM_HTTP_CLIENT), get()) }
    single<OsmMasterRepository> { OsmMasterRepositoryImpl(get()) }
    factoryOf(::ImportOsmAreaUseCase)
    factoryOf(::GetOsmMasterCountsUseCase)

    // --- map matching（issue #8） ---
    // 閾値（MapMatchingConfig）はここで差し替えられる。UIからは触らせない。
    single { MapMatchingConfig.DEFAULT }
    single<PassageRepository> { PassageRepositoryImpl(get()) }
    factoryOf(::RecomputePassagesUseCase)

    // --- 道の成長（issue #9） ---
    // 段階の閾値（GrowthConfig）もここで差し替えられる。way_growth は捨てて
    // 作り直せる導出キャッシュなので、差し替えたら RecomputeWayGrowthUseCase を流せばよい。
    single { GrowthConfig.DEFAULT }
    single<WayGrowthRepository> { WayGrowthRepositoryImpl(get()) }
    factoryOf(::RecomputeWayGrowthUseCase)
    // 「今回の散歩で育った道」は永続化しない（RecentGrowthRepository のKDoc）。
    // 書く側（再計算）と読む側（地図）が同じ1個を見る必要があるので single。
    single<RecentGrowthRepository> { InMemoryRecentGrowthRepository() }
    factoryOf(::ObserveGrowthUpdatesUseCase)
    // 散歩終了時（帰宅後）の入口。passage → way_growth の順で作り直す。
    // セッション終了イベントとの結線は AppViewModel（#10）。
    factoryOf(::RecomputeAfterWalkUseCase)

    // --- 押し忘れ救済（issue #7） ---
    // 歩数計（DailyStepsSource）の実装はプラットフォーム側（platformModule）。
    // 呼び出しタイミング（アプリ起動時）の結線は #16 で行う。
    single<CalendarDays> { SystemCalendarDays(get()) }
    single<StepImportRepository> { StepImportRepositoryImpl(get()) }
    factoryOf(::ImportDailyStepsUseCase)

    // --- 初回セットアップ（issue #6） ---
    // 秘密（APIキー・自宅座標）と非秘密（URL・モデル名・完了フラグ）の振り分けは
    // SetupRepositoryImpl に閉じる。UI層はUseCaseしか見ない。
    single<SetupRepository> { SetupRepositoryImpl(get(), get()) }
    single(LLM_HTTP_CLIENT) { llmHttpClient() }
    // #14 で LlmClient を入れるときは、この bind を差し替えれば画面はそのまま動く
    single<LlmConnectionTester> { HttpLlmConnectionTester(get(LLM_HTTP_CLIENT)) }

    factoryOf(::IsSetupCompletedUseCase)
    factoryOf(::LoadSetupSettingsUseCase)
    factoryOf(::TestLlmConnectionUseCase)
    factoryOf(::SaveWeatherSettingsUseCase)
    factoryOf(::RegisterHomeAnchorUseCase)
    factoryOf(::UpdateHomeBlurRadiusUseCase)
    factoryOf(::CompleteSetupUseCase)
}

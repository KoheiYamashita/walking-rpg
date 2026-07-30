package com.walkingrpg.shared.di

import com.walkingrpg.shared.data.CurrentLocationRepositoryImpl
import com.walkingrpg.shared.data.LocationPermissionRepositoryImpl
import com.walkingrpg.shared.data.SystemClock
import com.walkingrpg.shared.data.SystemInfoRepositoryImpl
import com.walkingrpg.shared.data.WalkRecorderImpl
import com.walkingrpg.shared.data.WalkSessionExporterImpl
import com.walkingrpg.shared.data.SetupRepositoryImpl
import com.walkingrpg.shared.data.WalkSessionRepositoryImpl
import com.walkingrpg.shared.data.codex.CodexProgressRepositoryImpl
import com.walkingrpg.shared.data.codex.InMemoryRecentCodexRepository
import com.walkingrpg.shared.data.createDatabase
import com.walkingrpg.shared.data.feedback.InMemoryWalkEventBus
import com.walkingrpg.shared.data.feedback.WalkFeedbackImpl
import com.walkingrpg.shared.data.growth.InMemoryRecentGrowthRepository
import com.walkingrpg.shared.data.growth.WayGrowthRepositoryImpl
import com.walkingrpg.shared.data.llm.AnthropicLlmClient
import com.walkingrpg.shared.data.llm.HttpLlmClientSelector
import com.walkingrpg.shared.data.llm.LlmCacheRepositoryImpl
import com.walkingrpg.shared.data.llm.LlmClientConnectionTester
import com.walkingrpg.shared.data.llm.OpenAiLlmClient
import com.walkingrpg.shared.data.llm.UtteranceLogRepositoryImpl
import com.walkingrpg.shared.data.llm.llmHttpClient
import com.walkingrpg.shared.data.matching.PassageRepositoryImpl
import com.walkingrpg.shared.data.osm.OsmMasterRepositoryImpl
import com.walkingrpg.shared.data.review.InMemoryPendingReviewRepository
import com.walkingrpg.shared.data.review.SystemTimeOfDayResolver
import com.walkingrpg.shared.data.osm.OverpassConfig
import com.walkingrpg.shared.data.osm.OverpassOsmAreaSource
import com.walkingrpg.shared.data.osm.osmHttpClient
import com.walkingrpg.shared.data.steps.StepImportRepositoryImpl
import com.walkingrpg.shared.data.steps.SystemCalendarDays
import com.walkingrpg.shared.data.weather.HttpWeatherProviderSelector
import com.walkingrpg.shared.data.weather.OpenMeteoWeatherProvider
import com.walkingrpg.shared.data.weather.OpenWeatherMapWeatherProvider
import com.walkingrpg.shared.data.weather.SessionWeatherRepositoryImpl
import com.walkingrpg.shared.data.weather.VisualCrossingWeatherProvider
import com.walkingrpg.shared.data.weather.weatherHttpClient
import com.walkingrpg.shared.domain.Clock
import com.walkingrpg.shared.domain.GetPlatformNameUseCase
import com.walkingrpg.shared.domain.SystemInfoRepository
import com.walkingrpg.shared.domain.codex.CodexConfig
import com.walkingrpg.shared.domain.codex.CodexProgressRepository
import com.walkingrpg.shared.domain.codex.GetCodexUseCase
import com.walkingrpg.shared.domain.codex.ObserveCodexUpdatesUseCase
import com.walkingrpg.shared.domain.codex.RecentCodexRepository
import com.walkingrpg.shared.domain.codex.RecomputeCodexProgressUseCase
import com.walkingrpg.shared.domain.feedback.ObserveWalkEventsUseCase
import com.walkingrpg.shared.domain.feedback.WalkEventBus
import com.walkingrpg.shared.domain.feedback.WalkFeedback
import com.walkingrpg.shared.domain.feedback.WalkFeedbackConfig
import com.walkingrpg.shared.domain.growth.GrowthConfig
import com.walkingrpg.shared.domain.growth.ObserveGrowthUpdatesUseCase
import com.walkingrpg.shared.domain.growth.RecentGrowthRepository
import com.walkingrpg.shared.domain.growth.RecomputeAfterWalkUseCase
import com.walkingrpg.shared.domain.growth.RecomputeWayGrowthUseCase
import com.walkingrpg.shared.domain.growth.WayGrowthRepository
import com.walkingrpg.shared.domain.llm.DrainLlmGenerationQueueUseCase
import com.walkingrpg.shared.domain.llm.GenerateWalkReviewRemarkUseCase
import com.walkingrpg.shared.domain.llm.GetPoiFlavorUseCase
import com.walkingrpg.shared.domain.llm.GetWalkRemarkContextUseCase
import com.walkingrpg.shared.domain.llm.GetSpeciesDescriptionUseCase
import com.walkingrpg.shared.domain.llm.LlmCacheRepository
import com.walkingrpg.shared.domain.llm.LlmClientSelector
import com.walkingrpg.shared.domain.llm.LlmGenerationConfig
import com.walkingrpg.shared.domain.llm.LlmGenerationQueue
import com.walkingrpg.shared.domain.llm.ObserveWalkReviewRemarkUseCase
import com.walkingrpg.shared.domain.llm.PrebatchPoiFlavorUseCase
import com.walkingrpg.shared.domain.llm.PrebatchSpeciesDescriptionUseCase
import com.walkingrpg.shared.domain.llm.UtteranceLogRepository
import com.walkingrpg.shared.domain.llm.WalkRemarkConfig
import com.walkingrpg.shared.domain.map.GetMapSceneUseCase
import com.walkingrpg.shared.domain.matching.MapMatchingConfig
import com.walkingrpg.shared.domain.matching.PassageRepository
import com.walkingrpg.shared.domain.matching.RecomputePassagesUseCase
import com.walkingrpg.shared.domain.osm.GetOsmMasterCountsUseCase
import com.walkingrpg.shared.domain.osm.ImportOsmAreaUseCase
import com.walkingrpg.shared.domain.osm.OsmAreaSource
import com.walkingrpg.shared.domain.osm.OsmMasterRepository
import com.walkingrpg.shared.domain.review.ConsumePendingReviewUseCase
import com.walkingrpg.shared.domain.review.GetWalkReviewUseCase
import com.walkingrpg.shared.domain.review.ObservePendingReviewUseCase
import com.walkingrpg.shared.domain.review.PendingReviewRepository
import com.walkingrpg.shared.domain.review.RequestPendingReviewUseCase
import com.walkingrpg.shared.domain.review.RequestReviewForFinishedWalkUseCase
import com.walkingrpg.shared.domain.review.TimeOfDayResolver
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
import com.walkingrpg.shared.domain.weather.FetchMissingSessionWeatherUseCase
import com.walkingrpg.shared.domain.weather.ObserveSessionWeatherUseCase
import com.walkingrpg.shared.domain.weather.SessionWeatherRepository
import com.walkingrpg.shared.domain.weather.WeatherFetchConfig
import com.walkingrpg.shared.domain.weather.WeatherProviderSelector
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
 * LLM呼び出し用のHTTPクライアント（issue #6 / #14）。
 * 疎通確認と生成で同じ1個を使う（セットアップで確認した経路がそのまま生成に使われる）。
 * リトライ方針・タイムアウトはOSM取り込み・天候とは違うので分けてある（`llmHttpClient`）。
 */
private val LLM_HTTP_CLIENT = named("llmHttpClient")

/**
 * 地点フレーバーの生成キュー（issue #14）。
 * [LlmGenerationQueue] の実装が2本になったので、注入先で取り違えないよう修飾子で分ける。
 */
private val POI_FLAVOR_QUEUE = named("poiFlavorQueue")

/** 図鑑の記述文の生成キュー（issue #13）。 */
private val SPECIES_DESCRIPTION_QUEUE = named("speciesDescriptionQueue")

/** 振り返りのパートナーの一言の生成キュー（issue #15）。 */
private val WALK_REVIEW_REMARK_QUEUE = named("walkReviewRemarkQueue")

/**
 * 天候取得用のHTTPクライアント（issue #11）。
 * リトライしない（失敗は次回起動時にやり直す）ぶん、他の2つとは方針が違う。
 */
private val WEATHER_HTTP_CLIENT = named("weatherHttpClient")

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
            walkFeedback = get(),
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

    // --- 図鑑システム（issue #13） ---
    // 近接半径・予兆の先行回数（CodexConfig）はここで差し替えられる。UIからは触らせない。
    // codex_progress は way_growth と同じく捨てて作り直せる導出キャッシュなので、
    // 差し替えたら RecomputeCodexProgressUseCase を流せばよい。
    // 種の閾値そのものは手書きのカタログ（SpeciesCatalog）側にある。
    single { CodexConfig.DEFAULT }
    single<CodexProgressRepository> { CodexProgressRepositoryImpl(get()) }
    factoryOf(::RecomputeCodexProgressUseCase)
    // 「今回の散歩で出た種」は永続化しない（RecentCodexRepository のKDoc）。
    // 書く側（再計算）と読む側（図鑑）が同じ1個を見る必要があるので single。
    single<RecentCodexRepository> { InMemoryRecentCodexRepository() }
    factoryOf(::ObserveCodexUpdatesUseCase)
    // 種カタログは手書きの定数（SpeciesCatalog）なのでDIに載せない＝既定値を使う。
    // factoryOf にすると Koin が List<Species> の定義を探して落ちる。
    factory {
        GetCodexUseCase(
            codexProgressRepository = get(),
            getSpeciesDescription = get(),
            recentCodexRepository = get(),
        )
    }

    // --- 歩行中フィードバック（issue #12） ---
    // 振動の回数制限（WalkFeedbackConfig）はここで差し替えられる。UIからは触らせない。
    single { WalkFeedbackConfig.DEFAULT }
    // 出す側（記録の収集）と読む側（画面）が同じ1個を見る必要があるので single。
    // 永続化しない理由は WalkEventBus のKDoc。
    single<WalkEventBus> { InMemoryWalkEventBus() }
    // 歩行中は導出テーブルを書かない「見込み」判定（LiveGrowthEstimator のKDoc）。
    // 確定するのは帰宅後の RecomputeAfterWalkUseCase。記録中セッションと1対1なので single。
    single<WalkFeedback> {
        WalkFeedbackImpl(
            osmMasterRepository = get(),
            passageRepository = get(),
            eventBus = get(),
            haptics = get(),
            matchingConfig = get(),
            growthConfig = get(),
            codexConfig = get(),
            feedbackConfig = get(),
        )
    }
    factoryOf(::ObserveWalkEventsUseCase)

    // --- 押し忘れ救済（issue #7） ---
    // 歩数計（DailyStepsSource）の実装はプラットフォーム側（platformModule）。
    // 呼び出しタイミング（アプリ起動時）の結線は AppViewModel（#16）。
    // 出口（パートナーが言及する）は GetWalkRemarkContextUseCase（#16）。
    single<CalendarDays> { SystemCalendarDays(get()) }
    single<StepImportRepository> { StepImportRepositoryImpl(get()) }
    factoryOf(::ImportDailyStepsUseCase)

    // --- 天候の後付け取得（issue #11） ---
    // 諦めるまでの猶予・1回の実行で投げる上限（WeatherFetchConfig）はここで差し替えられる。
    // どのプロバイダを使うかはユーザーの設定（WeatherSettings）で決まるので、
    // 3実装を全部登録して HttpWeatherProviderSelector に振り分けさせる。
    single { WeatherFetchConfig.DEFAULT }
    single(WEATHER_HTTP_CLIENT) { weatherHttpClient() }
    single { OpenMeteoWeatherProvider(get(WEATHER_HTTP_CLIENT)) }
    single { OpenWeatherMapWeatherProvider(get(WEATHER_HTTP_CLIENT)) }
    single { VisualCrossingWeatherProvider(get(WEATHER_HTTP_CLIENT)) }
    single<WeatherProviderSelector> { HttpWeatherProviderSelector(get(), get(), get()) }
    single<SessionWeatherRepository> { SessionWeatherRepositoryImpl(get()) }
    // 散歩終了時とアプリ起動時の両方から呼ぶ1本（AppViewModel で結線）。
    // UseCase では例外的に single にする：2つの呼び出し口が並行したときに
    // 同じセッションへ二度問い合わせないよう、実行を Mutex で直列化しており、
    // その Mutex は全ての呼び出しで同じ1個でなければ意味がない
    // （FetchMissingSessionWeatherUseCase のKDoc「実行は直列」）。
    singleOf(::FetchMissingSessionWeatherUseCase)
    // 振り返りが開いたままでも天候が埋まるように、読み口は購読（issue #15）
    factoryOf(::ObserveSessionWeatherUseCase)

    // --- LLM生成基盤（issue #14） ---
    // 件数上限・出力上限・従量回線での抑止（LlmGenerationConfig）はここで差し替えられる。
    // どのフォーマットを使うかはユーザーの設定（LlmConnectionSettings.format）で決まるので、
    // 2実装を両方登録して HttpLlmClientSelector に振り分けさせる（天候と同じ形）。
    single { LlmGenerationConfig.DEFAULT }
    // 疎通確認（issue #6）もこの1個を使う（LLM_HTTP_CLIENT のKDoc）
    single(LLM_HTTP_CLIENT) { llmHttpClient() }
    single { AnthropicLlmClient(get(LLM_HTTP_CLIENT)) }
    single { OpenAiLlmClient(get(LLM_HTTP_CLIENT)) }
    single<LlmClientSelector> { HttpLlmClientSelector(get(), get()) }
    single<LlmCacheRepository> { LlmCacheRepositoryImpl(get()) }
    // 生成キューは「未生成を数え直して作る」冪等なドレイン（LlmGenerationQueue のKDoc）。
    // 同じ型の実装が2本あるので named() で分ける（無修飾だと後から登録した方が黙って勝つ）。
    single<LlmGenerationQueue>(POI_FLAVOR_QUEUE) {
        PrebatchPoiFlavorUseCase(
            osmMasterRepository = get(),
            cacheRepository = get(),
            setupRepository = get(),
            clients = get(),
            networkStatus = get(),
            clock = get(),
            config = get(),
        )
    }
    single<LlmGenerationQueue>(SPECIES_DESCRIPTION_QUEUE) {
        PrebatchSpeciesDescriptionUseCase(
            cacheRepository = get(),
            setupRepository = get(),
            clients = get(),
            networkStatus = get(),
            clock = get(),
            config = get(),
        )
    }
    // 起動時と散歩終了時の両方から呼ぶ1本（AppViewModel で結線）。
    // UseCase では例外的に single にする：2つの呼び出し口が並行したときに
    // 同じ生成を二度投げないよう実行を Mutex で直列化しており、
    // その Mutex は全ての呼び出しで同じ1個でなければ意味がない
    // （DrainLlmGenerationQueueUseCase のKDoc「実行は直列」）。
    // 振り返りの一言（issue #15）。事前バッチ2本と違い、対象は直近の終了済みセッションだけで、
    // **従量回線でも投げる**（GenerateWalkReviewRemarkUseCase のKDoc「従量回線でも投げる」）。
    single<LlmGenerationQueue>(WALK_REVIEW_REMARK_QUEUE) {
        GenerateWalkReviewRemarkUseCase(
            walkSessionRepository = get(),
            getWalkReview = get(),
            getWalkRemarkContext = get(),
            cacheRepository = get(),
            utteranceLogRepository = get(),
            setupRepository = get(),
            clients = get(),
            networkStatus = get(),
            clock = get(),
            config = get(),
        )
    }
    single {
        DrainLlmGenerationQueueUseCase(
            walkReviewRemarkQueue = get(WALK_REVIEW_REMARK_QUEUE),
            speciesDescriptionQueue = get(SPECIES_DESCRIPTION_QUEUE),
            poiFlavorQueue = get(POI_FLAVOR_QUEUE),
        )
    }
    // 散歩中・図鑑の読み出し（通信しない）。生成できていないものは定型文で凌ぐ
    factoryOf(::GetPoiFlavorUseCase)
    factoryOf(::GetSpeciesDescriptionUseCase)
    // 振り返りの一言は「読み切り」ではなく購読（生成が届いたら差し替わる）
    factoryOf(::ObserveWalkReviewRemarkUseCase)

    // --- パートナーの一言（issue #16） ---
    // 記憶の深さ・押し忘れの言及範囲（WalkRemarkConfig）はここで差し替えられる。
    // UIからは触らせない。数字を動かすとプロンプトが変わる＝既存の一言は作り直される。
    single { WalkRemarkConfig.DEFAULT }
    // 発話履歴（utterance_log）は捨てられない追記ログ（UtteranceLogRepository のKDoc）。
    single<UtteranceLogRepository> { UtteranceLogRepositoryImpl(get()) }
    // 一言の材料を調達する1本。**生成側と読み側の両方がこれを呼ぶ**のがキャッシュの前提
    // （GetWalkRemarkContextUseCase のKDoc「生成側と読み側が同じ1本を通る」）。
    factory {
        GetWalkRemarkContextUseCase(
            walkSessionRepository = get(),
            passageRepository = get(),
            stepImportRepository = get(),
            utteranceLogRepository = get(),
            timeOfDayResolver = get(),
            calendarDays = get(),
            config = get(),
        )
    }

    // --- 振り返り（issue #15） ---
    // 数値サマリは確定データからの再計算で組む（通信しない）。閾値は成長・図鑑と同じ1個を使う
    // ＝振り返りと地図・図鑑で数え方がずれない。種カタログは手書きの定数なのでDIに載せない
    // （GetCodexUseCase と同じ理由：factoryOf にすると List<Species> の定義を探して落ちる）。
    factory {
        GetWalkReviewUseCase(
            walkSessionRepository = get(),
            passageRepository = get(),
            osmMasterRepository = get(),
            sessionWeatherRepository = get(),
            getCodex = get(),
            growthConfig = get(),
            codexConfig = get(),
        )
    }
    // 時間帯（パートナーの一言の材料）はタイムゾーンの知識が要るのでデータ層に置く。
    single<TimeOfDayResolver> { SystemTimeOfDayResolver() }
    // 「これから開く振り返り」は永続化しない（PendingReviewRepository のKDoc）。
    // 要求する側（散歩の終了・通知のタップ）と読む側（AppViewModel）が同じ1個を見るので single。
    single<PendingReviewRepository> { InMemoryPendingReviewRepository() }
    factoryOf(::ObservePendingReviewUseCase)
    factoryOf(::RequestPendingReviewUseCase)
    factoryOf(::ConsumePendingReviewUseCase)
    // 散歩終了イベントからの自動遷移（放置セッションは弾く）。結線は AppViewModel
    factoryOf(::RequestReviewForFinishedWalkUseCase)

    // --- 初回セットアップ（issue #6） ---
    // 秘密（APIキー・自宅座標）と非秘密（URL・モデル名・完了フラグ）の振り分けは
    // SetupRepositoryImpl に閉じる。UI層はUseCaseしか見ない。
    single<SetupRepository> { SetupRepositoryImpl(get(), get()) }
    // 疎通確認は生成と同じ経路（LlmClient）で行う。実装は data/llm 側の薄いアダプタで、
    // セットアップ画面（SetupViewModel）はこの bind の差し替えだけで #6 のまま動く
    single<LlmConnectionTester> { LlmClientConnectionTester(get()) }

    factoryOf(::IsSetupCompletedUseCase)
    factoryOf(::LoadSetupSettingsUseCase)
    factoryOf(::TestLlmConnectionUseCase)
    factoryOf(::SaveWeatherSettingsUseCase)
    factoryOf(::RegisterHomeAnchorUseCase)
    factoryOf(::UpdateHomeBlurRadiusUseCase)
    factoryOf(::CompleteSetupUseCase)
}

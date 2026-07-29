package com.walkingrpg.shared.domain.weather

import com.walkingrpg.shared.data.FakeWalkSessionRepository
import com.walkingrpg.shared.data.MutableClock
import com.walkingrpg.shared.domain.setup.WeatherProviderChoice
import com.walkingrpg.shared.domain.setup.WeatherSettings
import com.walkingrpg.shared.domain.walk.LocationSample
import com.walkingrpg.shared.domain.walk.SessionEndReason
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 天候の後付け取得（[FetchMissingSessionWeatherUseCase]）。
 *
 * 見たいのは4つ：
 * - 冪等（何度流しても行が増えない・取れているセッションは触らない）
 * - 失敗時に**行を作らない**（＝次回リトライ対象として残る）
 * - 諦め確定（取得可能期間を過ぎた／位置が無い）で UNKNOWN の行が入る
 * - 選択されたプロバイダに投げる
 */
class FetchMissingSessionWeatherUseCaseTest {

    private val clock = MutableClock(NOW_MS)
    private val sessions = FakeWalkSessionRepository()
    private val weathers = FakeSessionWeatherRepository()

    private fun useCase(
        provider: WeatherProvider = FakeWeatherProvider(),
        settings: WeatherSettings = WeatherSettings(),
        config: WeatherFetchConfig = WeatherFetchConfig.DEFAULT,
        selector: WeatherProviderSelector = FakeWeatherProviderSelector(provider),
    ) = FetchMissingSessionWeatherUseCase(
        sessionRepository = sessions,
        weatherRepository = weathers,
        setupRepository = WeatherSettingsSetupRepository(settings),
        providers = selector,
        clock = clock,
        config = config,
    )

    /** 終了済みのセッションを1本作る。[withSamples] が false なら測位が1件も無い散歩。 */
    private suspend fun finishedSession(
        endedAtMs: Long = NOW_MS - 60_000L,
        withSamples: Boolean = true,
    ): Long {
        val id = sessions.startSession(startedAtMs = endedAtMs - 1_800_000L)
        if (withSamples) {
            repeat(3) { index ->
                sessions.appendSample(
                    LocationSample(
                        sessionId = id,
                        timestampMs = endedAtMs - 1_800_000L + index * 600_000L,
                        latitude = 12.3456,
                        longitude = 34.5678,
                        accuracyMeters = 8.0,
                    ),
                )
            }
        }
        sessions.endSession(id, endedAtMs, SessionEndReason.MANUAL)
        weathers.finishedSessionIds = weathers.finishedSessionIds + id
        return id
    }

    @Test
    fun 未取得のセッションに天候を付ける() = runTest {
        val sessionId = finishedSession()
        val provider = FakeWeatherProvider(
            observation = WeatherObservation(WeatherCondition.RAIN, 14.5),
        )

        val result = useCase(provider).invoke()

        assertEquals(
            listOf(
                SessionWeather(
                    sessionId = sessionId,
                    condition = WeatherCondition.RAIN,
                    temperatureCelsius = 14.5,
                    fetchedAtMs = NOW_MS,
                ),
            ),
            result.fetched,
        )
        assertEquals(WeatherCondition.RAIN, weathers.weather(sessionId)?.condition)
        // 送った座標は丸めてある（WeatherQueryPlanner）
        assertEquals(listOf(12.35 to 34.57), provider.queries.map { it.latitude to it.longitude })
    }

    @Test
    fun 記録中のセッションは対象にしない() = runTest {
        sessions.startSession(startedAtMs = NOW_MS - 600_000L)
        val provider = FakeWeatherProvider()

        val result = useCase(provider).invoke()

        // そもそも「終了済みで行が無いセッション」に入らない（SQL側で絞る条件と同じ）
        assertEquals(emptyList(), result.fetched)
        assertEquals(0, provider.queries.size)
    }

    @Test
    fun 二度流しても行は増えず問い合わせも1回だけ() = runTest {
        val sessionId = finishedSession()
        val provider = FakeWeatherProvider()
        val useCase = useCase(provider)

        useCase()
        val second = useCase()

        assertEquals(emptyList(), second.fetched)
        assertEquals(1, provider.queries.size, "取得済みのセッションには二度と問い合わせない")
        assertEquals(1, weathers.saved.size)
        assertEquals(setOf(sessionId), weathers.rows().keys)
    }

    @Test
    fun 取得に失敗したら行を作らず次回に持ち越す() = runTest {
        val sessionId = finishedSession()
        val failing = FakeWeatherProvider(failure = WeatherUnavailableException("圏外"))

        val result = useCase(failing).invoke()

        assertEquals(listOf(sessionId), result.unresolvedSessionIds)
        assertEquals(emptyList(), weathers.saved, "失敗で行を作ると、もう取り直せなくなる")
        assertNull(weathers.weather(sessionId))

        // 通信が戻れば同じ1本でそのまま埋まる（＝圏外で終了しても後日埋まる）
        val recovered = useCase(FakeWeatherProvider()).invoke()
        assertEquals(listOf(sessionId), recovered.fetched.map { it.sessionId })
    }

    @Test
    fun 失敗が1件あっても残りのセッションを止めない() = runTest {
        val first = finishedSession(endedAtMs = NOW_MS - 7_200_000L)
        val second = finishedSession(endedAtMs = NOW_MS - 3_600_000L, withSamples = false)
        val third = finishedSession(endedAtMs = NOW_MS - 60_000L)
        val failing = FakeWeatherProvider(failure = WeatherUnavailableException("圏外"))

        val result = useCase(failing).invoke()

        assertEquals(listOf(first, third), result.unresolvedSessionIds)
        // 位置の無い散歩は通信せずに確定するので、失敗の巻き添えにならない
        assertEquals(listOf(second), result.confirmedUnknown.map { it.sessionId })
    }

    @Test
    fun 測位サンプルが無い散歩は天候不明で確定させる() = runTest {
        val sessionId = finishedSession(withSamples = false)
        val provider = FakeWeatherProvider()

        val result = useCase(provider).invoke()

        assertEquals(0, provider.queries.size, "訊く座標が無いのに問い合わせない")
        assertEquals(
            SessionWeather(
                sessionId = sessionId,
                condition = WeatherCondition.UNKNOWN,
                temperatureCelsius = null,
                fetchedAtMs = NOW_MS,
            ),
            result.confirmedUnknown.single(),
        )
        assertTrue(weathers.weather(sessionId)?.isKnown == false)

        // 確定したので、次に流してもリトライ対象に戻らない
        assertEquals(emptyList(), useCase(provider).invoke().confirmedUnknown)
    }

    @Test
    fun 取得可能期間を過ぎた散歩は諦めて天候不明で確定させる() = runTest {
        val config = WeatherFetchConfig(giveUpAfterMs = 14L * 24 * 60 * 60 * 1000)
        val old = finishedSession(endedAtMs = NOW_MS - config.giveUpAfterMs - 1)
        val fresh = finishedSession(endedAtMs = NOW_MS - config.giveUpAfterMs + 1)
        val provider = FakeWeatherProvider()

        val result = useCase(provider, config = config).invoke()

        assertEquals(listOf(old), result.confirmedUnknown.map { it.sessionId })
        assertEquals(listOf(fresh), result.fetched.map { it.sessionId })
        assertEquals(1, provider.queries.size, "諦めたぶんは通信しない")
    }

    @Test
    fun 実行1回あたりの問い合わせ件数に上限がある() = runTest {
        val ids = List(3) { finishedSession(endedAtMs = NOW_MS - (3 - it) * 60_000L) }
        val provider = FakeWeatherProvider()
        val config = WeatherFetchConfig(maxFetchesPerRun = 2)

        val first = useCase(provider, config = config).invoke()

        assertEquals(ids.take(2), first.fetched.map { it.sessionId }, "古い順に埋める")
        assertEquals(listOf(ids[2]), first.unresolvedSessionIds)

        // 残りは次の実行で続きから埋まる
        val second = useCase(provider, config = config).invoke()
        assertEquals(listOf(ids[2]), second.fetched.map { it.sessionId })
    }

    @Test
    fun 選択されたプロバイダに投げる() = runTest {
        finishedSession()
        val openMeteo = FakeWeatherProvider(choice = WeatherProviderChoice.OPEN_METEO)
        val visualCrossing = FakeWeatherProvider(
            choice = WeatherProviderChoice.VISUAL_CROSSING,
            observation = WeatherObservation(WeatherCondition.SNOW, -1.0),
        )
        val selector = FakeWeatherProviderSelector(
            mapOf(
                WeatherProviderChoice.OPEN_METEO to openMeteo,
                WeatherProviderChoice.OPEN_WEATHER_MAP to FakeWeatherProvider(),
                WeatherProviderChoice.VISUAL_CROSSING to visualCrossing,
            ),
        )
        val settings = WeatherSettings(
            provider = WeatherProviderChoice.VISUAL_CROSSING,
            apiKey = "test-key",
        )

        val result = useCase(settings = settings, selector = selector).invoke()

        assertEquals(WeatherCondition.SNOW, result.fetched.single().condition)
        assertEquals(0, openMeteo.queries.size)
        assertEquals(listOf("test-key"), visualCrossing.apiKeys, "APIキーは選んだ実装にだけ渡す")
    }

    @Test
    fun 対象が無ければ設定も読まない() = runTest {
        val setupRepository = WeatherSettingsSetupRepository()
        val useCase = FetchMissingSessionWeatherUseCase(
            sessionRepository = sessions,
            weatherRepository = weathers,
            setupRepository = setupRepository,
            providers = FakeWeatherProviderSelector(FakeWeatherProvider()),
            clock = clock,
        )

        assertEquals(FetchMissingSessionWeatherUseCase.Result(), useCase())
        // 埋める相手が居ないのにセキュアストレージ（APIキー）を触らない
        assertEquals(0, setupRepository.loadCount)
    }

    private companion object {
        const val NOW_MS = 1_700_000_000_000L
    }
}

package com.walkingrpg.shared.data.weather

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.walkingrpg.shared.data.WalkSessionRepositoryImpl
import com.walkingrpg.shared.data.db.WalkingRpgDatabase
import com.walkingrpg.shared.domain.walk.SessionEndReason
import com.walkingrpg.shared.domain.weather.SessionWeather
import com.walkingrpg.shared.domain.weather.WeatherCondition
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * `SessionWeather.sq` を実際のSQLite（インメモリ）で検証する。
 * フェイクでは確かめられない「SQLそのものの正しさ」だけをここで見る
 * ＝1セッション1行に収束するか、リトライ対象の絞り込み（LEFT JOIN）が正しいか。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionWeatherRepositoryImplTest {

    private lateinit var database: WalkingRpgDatabase

    /**
     * @param dispatcher 購読（`observe`）の流し先。テストスケジューラに乗せると、
     *  保存のあとに `runCurrent()` で決定的に受け取れる。
     */
    private fun repository(
        dispatcher: CoroutineDispatcher = Dispatchers.Default,
    ): SessionWeatherRepositoryImpl {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        WalkingRpgDatabase.Schema.create(driver)
        database = WalkingRpgDatabase(driver)
        return SessionWeatherRepositoryImpl(database, dispatcher)
    }

    private suspend fun finishedSession(startedAtMs: Long, endedAtMs: Long): Long {
        val sessions = WalkSessionRepositoryImpl(database)
        val id = sessions.startSession(startedAtMs)
        sessions.endSession(id, endedAtMs, SessionEndReason.MANUAL)
        return id
    }

    @Test
    fun 同じセッションを何度保存しても1行に収束する() = runTest {
        val repository = repository()
        val sessionId = finishedSession(startedAtMs = 1_000L, endedAtMs = 2_000L)

        repository.save(
            SessionWeather(sessionId, WeatherCondition.CLOUDY, 12.0, fetchedAtMs = 3_000L),
        )
        // プロバイダを切り替えて取り直した、の再現
        repository.save(
            SessionWeather(sessionId, WeatherCondition.RAIN, 11.5, fetchedAtMs = 4_000L),
        )

        assertEquals(
            SessionWeather(sessionId, WeatherCondition.RAIN, 11.5, fetchedAtMs = 4_000L),
            repository.weather(sessionId),
        )
        assertEquals(1L, database.sessionWeatherQueries.countSessionWeather().executeAsOne())
    }

    @Test
    fun 気温が取れなければNULLのまま残る() = runTest {
        val repository = repository()
        val sessionId = finishedSession(startedAtMs = 1_000L, endedAtMs = 2_000L)

        repository.save(SessionWeather(sessionId, WeatherCondition.FOG, null, fetchedAtMs = 3_000L))

        assertEquals(
            SessionWeather(sessionId, WeatherCondition.FOG, null, fetchedAtMs = 3_000L),
            repository.weather(sessionId),
        )
    }

    @Test
    fun 未取得のセッションはnullで返る() = runTest {
        val repository = repository()
        val sessionId = finishedSession(startedAtMs = 1_000L, endedAtMs = 2_000L)

        // 「行が無い」＝未取得。天候不明で確定した行（UNKNOWN）とは別物
        assertNull(repository.weather(sessionId))
    }

    @Test
    fun リトライ対象は終了済みで行の無いセッションだけ() = runTest {
        val repository = repository()
        val sessions = WalkSessionRepositoryImpl(database)
        val older = finishedSession(startedAtMs = 1_000L, endedAtMs = 5_000L)
        val newer = finishedSession(startedAtMs = 6_000L, endedAtMs = 9_000L)
        val fetched = finishedSession(startedAtMs = 10_000L, endedAtMs = 11_000L)
        val recording = sessions.startSession(startedAtMs = 12_000L)
        repository.save(
            SessionWeather(fetched, WeatherCondition.CLEAR, 20.0, fetchedAtMs = 12_000L),
        )

        val pending = repository.sessionIdsWithoutWeather()

        assertEquals(listOf(older, newer), pending, "古い順に返す（期限の近いものから埋める）")
        assertEquals(false, recording in pending, "記録中は訊く先が決まらないので対象にしない")
    }

    @Test
    fun 購読は保存で流れる() = runTest {
        // 振り返りを開いたまま後付け取得が終わると、天候の1行が増える（issue #15）
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val repository = repository(dispatcher)
        val sessionId = finishedSession(startedAtMs = 1_000L, endedAtMs = 2_000L)
        val fetched = SessionWeather(sessionId, WeatherCondition.CLEAR, 18.0, fetchedAtMs = 3_000L)

        val emissions = mutableListOf<SessionWeather?>()
        val job = launch(dispatcher) { repository.observe(sessionId).toList(emissions) }
        runCurrent()

        assertEquals(listOf<SessionWeather?>(null), emissions, "未取得のあいだは null")
        repository.save(fetched)
        runCurrent()
        assertEquals(listOf(null, fetched), emissions)
        job.cancel()
    }

    @Test
    fun 天候不明で確定させたセッションはリトライ対象に戻らない() = runTest {
        val repository = repository()
        val sessionId = finishedSession(startedAtMs = 1_000L, endedAtMs = 2_000L)

        repository.save(
            SessionWeather(sessionId, WeatherCondition.UNKNOWN, null, fetchedAtMs = 3_000L),
        )

        assertEquals(emptyList(), repository.sessionIdsWithoutWeather())
        assertEquals(WeatherCondition.UNKNOWN, repository.weather(sessionId)?.condition)
    }
}

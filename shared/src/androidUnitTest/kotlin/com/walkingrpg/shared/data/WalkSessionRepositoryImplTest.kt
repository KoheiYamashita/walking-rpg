package com.walkingrpg.shared.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.walkingrpg.shared.data.db.WalkingRpgDatabase
import com.walkingrpg.shared.domain.walk.LocationSample
import com.walkingrpg.shared.domain.walk.SessionEndReason
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `.sq` に書いたクエリを実際のSQLite（インメモリ）で検証する。
 * フェイクでは確かめられない「SQLそのものの正しさ」だけをここで見る。
 */
class WalkSessionRepositoryImplTest {

    private fun repository(): WalkSessionRepositoryImpl {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        WalkingRpgDatabase.Schema.create(driver)
        return WalkSessionRepositoryImpl(WalkingRpgDatabase(driver))
    }

    private fun sample(sessionId: Long, ts: Long) = LocationSample(
        sessionId = sessionId,
        timestampMs = ts,
        latitude = 0.0,
        longitude = 0.0,
        accuracyMeters = 5.0,
    )

    @Test
    fun 放置セッションは最後のサンプルの時刻で畳まれる() = runTest {
        val repository = repository()
        val sessionId = repository.startSession(startedAtMs = 1_000L)
        repository.appendSample(sample(sessionId, ts = 2_000L))
        repository.appendSample(sample(sessionId, ts = 5_000L))

        // 数日後に起動した想定でも、終了時刻に「今」は入らない
        repository.abandonOpenSessions()

        val session = checkNotNull(repository.session(sessionId))
        assertEquals(5_000L, session.endedAtMs)
        assertEquals(SessionEndReason.ABANDONED, session.endReason)
        assertEquals(4_000L, session.durationMs(nowMs = 500_000_000L))
    }

    @Test
    fun サンプルの無い放置セッションは開始時刻で畳まれる() = runTest {
        val repository = repository()
        val sessionId = repository.startSession(startedAtMs = 1_000L)

        repository.abandonOpenSessions()

        assertEquals(1_000L, checkNotNull(repository.session(sessionId)).endedAtMs)
    }

    @Test
    fun 放置セッションごとに自分の最後のサンプルが使われる() = runTest {
        val repository = repository()
        val first = repository.startSession(startedAtMs = 1_000L)
        val second = repository.startSession(startedAtMs = 10_000L)
        repository.appendSample(sample(first, ts = 3_000L))
        repository.appendSample(sample(second, ts = 12_000L))

        repository.abandonOpenSessions()

        assertEquals(3_000L, checkNotNull(repository.session(first)).endedAtMs)
        assertEquals(12_000L, checkNotNull(repository.session(second)).endedAtMs)
    }

    @Test
    fun 確定済みのセッションは畳み直されない() = runTest {
        val repository = repository()
        val sessionId = repository.startSession(startedAtMs = 1_000L)
        repository.appendSample(sample(sessionId, ts = 2_000L))
        repository.endSession(sessionId, endedAtMs = 4_000L, reason = SessionEndReason.MANUAL)

        val abandoned = repository.abandonOpenSessions()

        val session = checkNotNull(repository.session(sessionId))
        assertEquals(4_000L, session.endedAtMs)
        assertEquals(SessionEndReason.MANUAL, session.endReason)
        assertEquals(emptyList(), abandoned, "確定済みは返さない")
    }

    @Test
    fun 畳んだセッションのIDを返す() = runTest {
        // 呼び出し側はこのIDで導出（passage → way_growth）を作り直す
        val repository = repository()
        val first = repository.startSession(startedAtMs = 1_000L)
        val second = repository.startSession(startedAtMs = 10_000L)
        repository.endSession(second, endedAtMs = 11_000L, reason = SessionEndReason.MANUAL)
        val third = repository.startSession(startedAtMs = 20_000L)

        assertEquals(listOf(first, third), repository.abandonOpenSessions())
        // 畳んだあとは対象が無くなる（同じセッションを二度作り直さない）
        assertEquals(emptyList(), repository.abandonOpenSessions())
    }
}

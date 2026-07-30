package com.walkingrpg.shared.data.llm

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.walkingrpg.shared.data.db.WalkingRpgDatabase
import com.walkingrpg.shared.domain.llm.RemarkAngle
import com.walkingrpg.shared.domain.llm.UtteranceRecord
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `UtteranceLog.sq` を実際のSQLite（インメモリ）で検証する。
 * フェイクでは確かめられない「SQLそのものの正しさ」だけをここで見る：
 * - セッション単位の削除→挿入が本当に1セッション1行に収束するか
 * - 除外の絞り（`said_at <` と `session_id !=`）が効いているか
 */
class UtteranceLogRepositoryImplTest {

    private val startedAtMs = 1_767_225_600_000L

    private fun repository(): UtteranceLogRepositoryImpl {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        WalkingRpgDatabase.Schema.create(driver)
        return UtteranceLogRepositoryImpl(WalkingRpgDatabase(driver))
    }

    private fun record(
        sessionId: Long,
        angle: RemarkAngle = RemarkAngle.MEMORY,
        placeRef: String = "way:1",
        saidAtMs: Long = startedAtMs,
    ) = UtteranceRecord(
        sessionId = sessionId,
        placeRef = placeRef,
        angle = angle,
        saidAtMs = saidAtMs,
    )

    @Test
    fun 同じセッションを何度書いても1行に収束する() = runTest {
        val repository = repository()

        repository.replaceSessionUtterances(1L, listOf(record(1L, RemarkAngle.MEMORY)))
        repository.replaceSessionUtterances(1L, listOf(record(1L, RemarkAngle.GROWTH)))

        assertEquals(listOf(record(1L, RemarkAngle.GROWTH)), repository.utterances(1L))
    }

    @Test
    fun 空で置き換えると行が消える() = runTest {
        val repository = repository()
        repository.replaceSessionUtterances(1L, listOf(record(1L)))

        repository.replaceSessionUtterances(1L, emptyList())

        assertTrue(repository.utterances(1L).isEmpty())
    }

    @Test
    fun その場所の履歴だけが新しい順に返る() = runTest {
        val repository = repository()
        repository.replaceSessionUtterances(
            1L,
            listOf(record(1L, RemarkAngle.MEMORY, saidAtMs = startedAtMs - 3_000L)),
        )
        repository.replaceSessionUtterances(
            2L,
            listOf(record(2L, RemarkAngle.GROWTH, saidAtMs = startedAtMs - 2_000L)),
        )
        // 別の場所（day）ぶんは混ざらない
        repository.replaceSessionUtterances(
            3L,
            listOf(record(3L, RemarkAngle.WEATHER, placeRef = "day", saidAtMs = startedAtMs - 1_000L)),
        )

        val recent = repository.recentUtterances(
            placeRef = "way:1",
            beforeMs = startedAtMs,
            excludeSessionId = 9L,
            limit = 12,
        )

        assertEquals(listOf(RemarkAngle.GROWTH, RemarkAngle.MEMORY), recent.map { it.angle })
    }

    @Test
    fun 自分自身と自分より後の行は返さない() = runTest {
        // 読み側が指紋を計算し直したときに集合が変わると、生成済みの一言が使われなくなる
        val repository = repository()
        repository.replaceSessionUtterances(
            1L,
            listOf(record(1L, RemarkAngle.MEMORY, saidAtMs = startedAtMs - 86_400_000L)),
        )
        repository.replaceSessionUtterances(
            2L,
            listOf(record(2L, RemarkAngle.GROWTH, saidAtMs = startedAtMs)),
        )
        repository.replaceSessionUtterances(
            3L,
            listOf(record(3L, RemarkAngle.WEATHER, saidAtMs = startedAtMs + 86_400_000L)),
        )

        val recent = repository.recentUtterances(
            placeRef = "way:1",
            beforeMs = startedAtMs,
            excludeSessionId = 2L,
            limit = 12,
        )

        assertEquals(listOf(RemarkAngle.MEMORY), recent.map { it.angle })
    }

    @Test
    fun 件数の上限が効く() = runTest {
        val repository = repository()
        RemarkAngle.entries.forEachIndexed { index, angle ->
            repository.replaceSessionUtterances(
                index + 1L,
                listOf(record(index + 1L, angle, saidAtMs = startedAtMs - (index + 1) * 1_000L)),
            )
        }

        val recent = repository.recentUtterances(
            placeRef = "way:1",
            beforeMs = startedAtMs,
            excludeSessionId = 999L,
            limit = 3,
        )

        assertEquals(3, recent.size)
        assertEquals(
            RemarkAngle.entries.take(3),
            recent.map { it.angle },
            "新しい順（いちばん最近言った3件）",
        )
    }
}

package com.walkingrpg.shared.data.snapshot

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.walkingrpg.shared.data.db.WalkingRpgDatabase
import com.walkingrpg.shared.domain.snapshot.CalendarMonth
import com.walkingrpg.shared.domain.snapshot.MonthlySnapshotStats
import com.walkingrpg.shared.domain.snapshot.Snapshot
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `Snapshot.sq` を実際のSQLite（インメモリ）で検証する。
 *
 * フェイクでは確かめられない「SQLそのものの正しさ」だけをここで見る：
 * - `INSERT OR IGNORE` が本当に既存の行を守るか（一度作った月は壊さない）
 * - `stats_json` がJSONを往復して元の数値に戻るか
 * - 一覧が新しい月から返るか（アルバムの並び）
 */
class SnapshotRepositoryImplTest {

    private val june = CalendarMonth("2026-06")
    private val may = CalendarMonth("2026-05")

    private val juneStats = MonthlySnapshotStats(
        distanceMeters = 23_400.5,
        newWayCount = 12,
        discoveredSpeciesNames = listOf("テストトンボ", "テストカワセミ"),
        sessionCount = 9,
    )

    private fun repository(): SnapshotRepositoryImpl {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        WalkingRpgDatabase.Schema.create(driver)
        return SnapshotRepositoryImpl(WalkingRpgDatabase(driver))
    }

    private fun snapshot(
        month: CalendarMonth,
        stats: MonthlySnapshotStats = juneStats,
        createdAtMs: Long = 1_000L,
    ) = Snapshot(
        month = month,
        imagePath = "snapshots/${month.iso}.png",
        stats = stats,
        createdAtMs = createdAtMs,
    )

    @Test
    fun 保存した1枚が数値ごと往復する() = runTest {
        val repository = repository()

        assertTrue(repository.insertIfAbsent(snapshot(june)))

        val saved = repository.snapshot(june)!!
        assertEquals(june, saved.month)
        assertEquals("snapshots/2026-06.png", saved.imagePath)
        assertEquals(juneStats, saved.stats, "stats_json がJSONを往復して戻る")
        assertEquals(1_000L, saved.createdAtMs)
    }

    @Test
    fun 発見が0件の月も往復する() = runTest {
        val repository = repository()
        val empty = MonthlySnapshotStats(
            distanceMeters = 0.0,
            newWayCount = 0,
            discoveredSpeciesNames = emptyList(),
            sessionCount = 1,
        )

        repository.insertIfAbsent(snapshot(june, stats = empty))

        assertEquals(empty, repository.snapshot(june)?.stats)
    }

    @Test
    fun 既にある月は書き換わらない() = runTest {
        val repository = repository()
        repository.insertIfAbsent(snapshot(june, createdAtMs = 1L))

        val second = repository.insertIfAbsent(
            snapshot(
                june,
                stats = juneStats.copy(distanceMeters = 999.0),
                createdAtMs = 2L,
            ),
        )

        assertFalse(second, "入らなかったことが呼び出し側に伝わる")
        val saved = repository.snapshot(june)!!
        assertEquals(23_400.5, saved.stats.distanceMeters, "最初に焼いた数値が残る")
        assertEquals(1L, saved.createdAtMs)
        assertEquals(1, repository.count())
    }

    @Test
    fun 一覧は新しい月から返る() = runTest {
        val repository = repository()
        // わざと新しい月から入れて、並びが挿入順ではないことを見る
        repository.insertIfAbsent(snapshot(june))
        repository.insertIfAbsent(snapshot(may))
        repository.insertIfAbsent(snapshot(CalendarMonth("2025-12")))

        assertEquals(
            listOf("2026-06", "2026-05", "2025-12"),
            repository.snapshots().map { it.month.iso },
        )
    }

    @Test
    fun 作っていない月は取れない() = runTest {
        val repository = repository()
        repository.insertIfAbsent(snapshot(june))

        assertNull(repository.snapshot(may), "生成の判定はこの null で行う")
        assertEquals(1, repository.count())
    }
}

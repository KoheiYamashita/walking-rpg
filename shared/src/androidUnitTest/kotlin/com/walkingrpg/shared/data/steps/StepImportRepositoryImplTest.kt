package com.walkingrpg.shared.data.steps

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.walkingrpg.shared.data.db.WalkingRpgDatabase
import com.walkingrpg.shared.domain.steps.CalendarDay
import com.walkingrpg.shared.domain.steps.StepImport
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * `StepImport.sq` を実際のSQLite（インメモリ）で検証する。
 * フェイクでは確かめられない「SQLそのものの正しさ」だけをここで見る
 * ＝日付を主キーにした `INSERT OR REPLACE` が本当に1日1行に収束するか。
 */
class StepImportRepositoryImplTest {

    private val yesterday = CalendarDay("2026-03-01")
    private val today = CalendarDay("2026-03-02")

    private fun repository(): StepImportRepositoryImpl {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        WalkingRpgDatabase.Schema.create(driver)
        return StepImportRepositoryImpl(WalkingRpgDatabase(driver))
    }

    @Test
    fun 同じ日を何度保存しても1行に収束する() = runTest {
        val repository = repository()

        repository.upsert(StepImport(today, steps = 1_300, distanceEstimateMeters = 950.0))
        repository.upsert(StepImport(today, steps = 9_800, distanceEstimateMeters = 7_200.0))

        assertEquals(
            listOf(StepImport(today, steps = 9_800, distanceEstimateMeters = 7_200.0)),
            repository.stepImports(),
        )
    }

    @Test
    fun 日付で1日ぶんを引ける() = runTest {
        val repository = repository()
        repository.upsert(StepImport(yesterday, steps = 8_200, distanceEstimateMeters = 6_100.0))
        repository.upsert(StepImport(today, steps = 1_300, distanceEstimateMeters = 950.0))

        // 翌日のパートナーの一言（#16）が見るのはこの引き方
        assertEquals(
            StepImport(yesterday, steps = 8_200, distanceEstimateMeters = 6_100.0),
            repository.stepImport(yesterday),
        )
        assertNull(repository.stepImport(CalendarDay("2026-02-28")), "取り込んでいない日は null")
    }

    @Test
    fun 距離が取れない日はNULLのまま残る() = runTest {
        val repository = repository()
        repository.upsert(StepImport(today, steps = 4_000, distanceEstimateMeters = null))

        assertEquals(
            StepImport(today, steps = 4_000, distanceEstimateMeters = null),
            repository.stepImport(today),
        )
    }

    @Test
    fun 一覧は日付順で返る() = runTest {
        val repository = repository()
        repository.upsert(StepImport(today, steps = 1_300, distanceEstimateMeters = null))
        repository.upsert(StepImport(yesterday, steps = 8_200, distanceEstimateMeters = null))

        assertEquals(listOf(yesterday, today), repository.stepImports().map { it.day })
    }
}

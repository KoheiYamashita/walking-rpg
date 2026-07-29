package com.walkingrpg.shared.data.steps

import com.walkingrpg.shared.data.db.WalkingRpgDatabase
import com.walkingrpg.shared.domain.steps.CalendarDay
import com.walkingrpg.shared.domain.steps.StepImport
import com.walkingrpg.shared.domain.steps.StepImportRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [StepImportRepository] のSQLDelight実装。
 *
 * ドメインモデル⇄DBスキーマの変換はこのクラスに閉じる（architecture.md §2）。
 * 保存は日付を主キーにした `INSERT OR REPLACE`（StepImport.sq）＝同じ日を何度取り込んでも1行。
 */
internal class StepImportRepositoryImpl(
    private val database: WalkingRpgDatabase,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : StepImportRepository {

    private val imports get() = database.stepImportQueries

    override suspend fun upsert(stepImport: StepImport): Unit = withContext(dispatcher) {
        imports.upsertStepImport(
            date = stepImport.day.iso,
            steps = stepImport.steps.toLong(),
            distance_estimate = stepImport.distanceEstimateMeters,
        )
    }

    override suspend fun stepImport(day: CalendarDay): StepImport? = withContext(dispatcher) {
        imports.selectStepImport(day.iso).executeAsOneOrNull()?.let { row ->
            StepImport(
                day = CalendarDay(row.date),
                steps = row.steps.toInt(),
                distanceEstimateMeters = row.distance_estimate,
            )
        }
    }

    override suspend fun stepImports(): List<StepImport> = withContext(dispatcher) {
        imports.selectAllStepImports().executeAsList().map { row ->
            StepImport(
                day = CalendarDay(row.date),
                steps = row.steps.toInt(),
                distanceEstimateMeters = row.distance_estimate,
            )
        }
    }
}

package com.walkingrpg.shared.data.matching

import com.walkingrpg.shared.data.db.WalkingRpgDatabase
import com.walkingrpg.shared.domain.matching.Passage
import com.walkingrpg.shared.domain.matching.PassageRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [PassageRepository] のSQLDelight実装。
 *
 * 保存はセッション単位の**全削除→挿入を1トランザクション**で行う（Passage.sq）。
 * 途中で失敗しても直前の状態のまま残るのはトランザクションの効果で、
 * 「半分だけ新しい閾値で計算された通過」が残ることはない。
 */
internal class PassageRepositoryImpl(
    private val database: WalkingRpgDatabase,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : PassageRepository {

    private val passages get() = database.passageQueries

    override suspend fun replaceSessionPassages(
        sessionId: Long,
        passages: List<Passage>,
    ): Unit = withContext(dispatcher) {
        database.transaction {
            this@PassageRepositoryImpl.passages.deletePassagesBySession(sessionId)
            passages.forEach { passage ->
                this@PassageRepositoryImpl.passages.insertPassage(
                    session_id = sessionId,
                    way_id = passage.wayId,
                    ts = passage.timestampMs,
                )
            }
        }
    }

    override suspend fun passages(sessionId: Long): List<Passage> = withContext(dispatcher) {
        passages.selectPassagesBySession(sessionId).executeAsList().map { row ->
            Passage(
                sessionId = row.session_id,
                wayId = row.way_id,
                timestampMs = row.ts,
            )
        }
    }
}

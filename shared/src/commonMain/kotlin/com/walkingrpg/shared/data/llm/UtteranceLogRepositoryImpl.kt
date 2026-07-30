package com.walkingrpg.shared.data.llm

import com.walkingrpg.shared.data.db.WalkingRpgDatabase
import com.walkingrpg.shared.data.db.Utterance_log
import com.walkingrpg.shared.domain.llm.RemarkAngle
import com.walkingrpg.shared.domain.llm.UtteranceLogRepository
import com.walkingrpg.shared.domain.llm.UtteranceRecord
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [UtteranceLogRepository] のSQLDelight実装。
 *
 * ドメインモデル⇄DBスキーマの変換はこのクラスに閉じる（architecture.md §2）。
 * 書き込みはセッション単位の**削除→挿入を1トランザクション**（`UtteranceLog.sq`）。
 */
internal class UtteranceLogRepositoryImpl(
    private val database: WalkingRpgDatabase,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : UtteranceLogRepository {

    private val utterances get() = database.utteranceLogQueries

    override suspend fun recentUtterances(
        placeRef: String,
        beforeMs: Long,
        excludeSessionId: Long,
        limit: Int,
    ): List<UtteranceRecord> = withContext(dispatcher) {
        utterances.selectRecentUtterancesByPlace(
            placeRef = placeRef,
            beforeMs = beforeMs,
            excludeSessionId = excludeSessionId,
            limit = limit.toLong(),
        ).executeAsList().mapNotNull { it.toRecord() }
    }

    override suspend fun replaceSessionUtterances(
        sessionId: Long,
        records: List<UtteranceRecord>,
    ): Unit = withContext(dispatcher) {
        database.transaction {
            utterances.deleteUtterancesBySession(sessionId)
            records.forEach { record ->
                utterances.insertUtterance(
                    session_id = record.sessionId,
                    place_ref = record.placeRef,
                    angle = record.angle.name,
                    said_at = record.saidAtMs,
                )
            }
        }
    }

    override suspend fun utterances(sessionId: Long): List<UtteranceRecord> =
        withContext(dispatcher) {
            utterances.selectUtterancesBySession(sessionId).executeAsList()
                .mapNotNull { it.toRecord() }
        }
}

/**
 * DBの行 → ドメインの1件。
 *
 * 知らない `angle` の行は落とす（`RemarkAngle` を削った／改名したあとの古い行）。
 * 落としても失われるのは「その切り口をもう使った」という抑止だけで、
 * 一言そのものは出る（無言にはならない）。
 */
private fun Utterance_log.toRecord(): UtteranceRecord? {
    val parsed = RemarkAngle.entries.firstOrNull { it.name == angle } ?: return null
    return UtteranceRecord(
        sessionId = session_id,
        placeRef = place_ref,
        angle = parsed,
        saidAtMs = said_at,
    )
}

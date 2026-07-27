package com.walkingrpg.shared.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.walkingrpg.shared.data.db.WalkingRpgDatabase
import com.walkingrpg.shared.domain.walk.LocationSample
import com.walkingrpg.shared.domain.walk.SessionEndReason
import com.walkingrpg.shared.domain.walk.WalkSession
import com.walkingrpg.shared.domain.walk.WalkSessionRepository
import com.walkingrpg.shared.domain.walk.WalkSessionSummary
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * [WalkSessionRepository] のSQLDelight実装。
 *
 * ドメインモデル⇄DBスキーマの変換はこのクラスに閉じる（architecture.md §2）。
 */
internal class WalkSessionRepositoryImpl(
    private val database: WalkingRpgDatabase,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : WalkSessionRepository {

    private val sessions get() = database.walkSessionQueries
    private val samples get() = database.locationSampleQueries

    override suspend fun startSession(startedAtMs: Long): Long = withContext(dispatcher) {
        sessions.transactionWithResult {
            sessions.insertSession(startedAtMs)
            sessions.lastInsertedId().executeAsOne()
        }
    }

    override suspend fun appendSample(sample: LocationSample): Unit = withContext(dispatcher) {
        samples.insertSample(
            session_id = sample.sessionId,
            ts = sample.timestampMs,
            lat = sample.latitude,
            lon = sample.longitude,
            accuracy = sample.accuracyMeters,
        )
    }

    override suspend fun endSession(
        sessionId: Long,
        endedAtMs: Long,
        reason: SessionEndReason,
    ): Unit = withContext(dispatcher) {
        sessions.endSession(endedAt = endedAtMs, endReason = reason.name, id = sessionId)
    }

    // 終了時刻は「最後のサンプルのts（無ければstarted_at）」で、SQL側（WalkSession.sq）で決める。
    override suspend fun abandonOpenSessions(): Unit = withContext(dispatcher) {
        sessions.abandonOpenSessions(endReason = SessionEndReason.ABANDONED.name)
    }

    override fun observeSessions(): Flow<List<WalkSessionSummary>> =
        sessions.selectSessionSummaries()
            .asFlow()
            .mapToList(dispatcher)
            .map { rows ->
                rows.map { row ->
                    WalkSessionSummary(
                        session = WalkSession(
                            id = row.id,
                            startedAtMs = row.started_at,
                            endedAtMs = row.ended_at,
                            endReason = row.end_reason.toEndReason(),
                        ),
                        sampleCount = row.sample_count.toInt(),
                    )
                }
            }

    override suspend fun samples(sessionId: Long): List<LocationSample> = withContext(dispatcher) {
        samples.selectSamplesBySession(sessionId).executeAsList().map { row ->
            LocationSample(
                sessionId = row.session_id,
                timestampMs = row.ts,
                latitude = row.lat,
                longitude = row.lon,
                accuracyMeters = row.accuracy,
            )
        }
    }

    override suspend fun session(sessionId: Long): WalkSession? = withContext(dispatcher) {
        sessions.selectSession(sessionId).executeAsOneOrNull()?.let { row ->
            WalkSession(
                id = row.id,
                startedAtMs = row.started_at,
                endedAtMs = row.ended_at,
                endReason = row.end_reason.toEndReason(),
            )
        }
    }
}

/** DBに入っている文字列 → ドメインのenum。未知の値は「不明」としてnullに落とす。 */
private fun String?.toEndReason(): SessionEndReason? =
    this?.let { name -> SessionEndReason.entries.firstOrNull { it.name == name } }

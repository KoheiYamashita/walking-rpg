package com.walkingrpg.shared.data

import com.walkingrpg.shared.domain.Clock
import com.walkingrpg.shared.domain.walk.LocationFix
import com.walkingrpg.shared.domain.walk.LocationSample
import com.walkingrpg.shared.domain.walk.SessionEndReason
import com.walkingrpg.shared.domain.walk.WalkSession
import com.walkingrpg.shared.domain.walk.WalkSessionRepository
import com.walkingrpg.shared.domain.walk.WalkSessionSummary
import com.walkingrpg.shared.platform.LocationProvider
import com.walkingrpg.shared.platform.SessionKeeper
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** テスト用の差し替え実装。 */

internal class MutableClock(var nowMs: Long = 0L) : Clock {
    override fun nowMillis(): Long = nowMs
}

internal class FakeSessionKeeper : SessionKeeper {
    var started = 0
        private set
    var stopped = 0
        private set

    override fun start() {
        started++
    }

    override fun stop() {
        stopped++
    }
}

internal class FakeLocationProvider(
    private val failure: Throwable? = null,
) : LocationProvider {

    val fixes = MutableSharedFlow<LocationFix>()
    var requestedIntervalMs: Long? = null
        private set

    override fun updates(intervalMs: Long): Flow<LocationFix> {
        requestedIntervalMs = intervalMs
        failure?.let { throw it }
        return fixes
    }
}

internal class FakeWalkSessionRepository : WalkSessionRepository {

    private val sessionsState = MutableStateFlow<List<WalkSession>>(emptyList())
    private val samplesBySession = mutableMapOf<Long, MutableList<LocationSample>>()
    private var nextId = 1L

    val sessions: List<WalkSession> get() = sessionsState.value

    fun samplesOf(sessionId: Long): List<LocationSample> =
        samplesBySession[sessionId].orEmpty()

    override suspend fun startSession(startedAtMs: Long): Long {
        val session = WalkSession(id = nextId++, startedAtMs = startedAtMs)
        sessionsState.value = sessionsState.value + session
        return session.id
    }

    override suspend fun appendSample(sample: LocationSample) {
        samplesBySession.getOrPut(sample.sessionId) { mutableListOf() }.add(sample)
    }

    override suspend fun endSession(
        sessionId: Long,
        endedAtMs: Long,
        reason: SessionEndReason,
    ) {
        sessionsState.value = sessionsState.value.map { session ->
            if (session.id == sessionId && session.isOpen) {
                session.copy(endedAtMs = endedAtMs, endReason = reason)
            } else {
                session
            }
        }
    }

    override suspend fun abandonOpenSessions(endedAtMs: Long) {
        sessionsState.value = sessionsState.value.map { session ->
            if (session.isOpen) {
                session.copy(endedAtMs = endedAtMs, endReason = SessionEndReason.ABANDONED)
            } else {
                session
            }
        }
    }

    override fun observeSessions(): Flow<List<WalkSessionSummary>> =
        sessionsState.map { sessions ->
            sessions.sortedByDescending { it.startedAtMs }.map { session ->
                WalkSessionSummary(session, samplesOf(session.id).size)
            }
        }

    override suspend fun samples(sessionId: Long): List<LocationSample> = samplesOf(sessionId)

    override suspend fun session(sessionId: Long): WalkSession? =
        sessionsState.value.firstOrNull { it.id == sessionId }
}

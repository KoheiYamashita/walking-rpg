package com.walkingrpg.shared.data

import com.walkingrpg.shared.domain.Clock
import com.walkingrpg.shared.domain.setup.HomeAnchor
import com.walkingrpg.shared.domain.setup.LlmConnectionSettings
import com.walkingrpg.shared.domain.setup.SetupRepository
import com.walkingrpg.shared.domain.setup.WeatherSettings
import com.walkingrpg.shared.domain.walk.LocationFix
import com.walkingrpg.shared.domain.walk.LocationSample
import com.walkingrpg.shared.domain.walk.SessionEndReason
import com.walkingrpg.shared.domain.walk.WalkSession
import com.walkingrpg.shared.domain.walk.WalkSessionRepository
import com.walkingrpg.shared.domain.walk.WalkSessionSummary
import com.walkingrpg.shared.platform.LocationProvider
import com.walkingrpg.shared.platform.SessionKeeper
import com.walkingrpg.shared.platform.WalkNotifier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asFlow
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

internal class FakeWalkNotifier : WalkNotifier {
    /** 出した「おかえり」通知の長さ（ms）。件数と中身の両方を見たいので列で持つ。 */
    val homecomings = mutableListOf<Long>()

    override fun notifyHomecoming(durationMs: Long) {
        homecomings += durationMs
    }
}

/**
 * 自宅の登録だけを持つ [SetupRepository]。記録の結線で見たいのは
 * 「自宅が読めるか／未登録か」だけなので、他は使わない前提で落とす。
 */
internal class FakeSetupRepository(
    private val homeAnchor: HomeAnchor? = null,
    /** セキュアストレージが読めない状況（端末の鍵が壊れた等）の再現。 */
    private val homeAnchorFailure: Throwable? = null,
) : SetupRepository {

    override suspend fun loadHomeAnchor(): HomeAnchor? {
        homeAnchorFailure?.let { throw it }
        return homeAnchor
    }

    override suspend fun loadLlmConnection(): LlmConnectionSettings? = notUsed()
    override suspend fun saveLlmConnection(settings: LlmConnectionSettings) = notUsed()
    override suspend fun loadWeatherSettings(): WeatherSettings = notUsed()
    override suspend fun saveWeatherSettings(settings: WeatherSettings) = notUsed()
    override suspend fun saveHomeAnchor(anchor: HomeAnchor) = notUsed()
    override suspend fun isSetupCompleted(): Boolean = notUsed()
    override suspend fun markSetupCompleted() = notUsed()

    private fun notUsed(): Nothing = error("記録の結線では使わない")
}

internal class FakeLocationProvider(
    private val failure: Throwable? = null,
    /** 指定すると、この列を流したあとストリームを完了する（測位側が止まった状況の再現）。 */
    private val finiteFixes: List<LocationFix>? = null,
) : LocationProvider {

    val fixes = MutableSharedFlow<LocationFix>()
    var requestedIntervalMs: Long? = null
        private set

    override fun updates(intervalMs: Long): Flow<LocationFix> {
        requestedIntervalMs = intervalMs
        failure?.let { throw it }
        return finiteFixes?.asFlow() ?: fixes
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

    // 本実装（SQL）と同じく、終了時刻は最後のサンプルの時刻。無ければ開始時刻。
    override suspend fun abandonOpenSessions(): List<Long> {
        val abandoned = sessionsState.value.filter { it.isOpen }.map { it.id }
        sessionsState.value = sessionsState.value.map { session ->
            if (session.isOpen) {
                session.copy(
                    endedAtMs = samplesOf(session.id).maxOfOrNull { it.timestampMs }
                        ?: session.startedAtMs,
                    endReason = SessionEndReason.ABANDONED,
                )
            } else {
                session
            }
        }
        return abandoned
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

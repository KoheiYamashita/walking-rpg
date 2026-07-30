package com.walkingrpg.shared.domain.review

import com.walkingrpg.shared.data.FakeWalkSessionRepository
import com.walkingrpg.shared.data.review.InMemoryPendingReviewRepository
import com.walkingrpg.shared.domain.walk.SessionEndReason
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 散歩の終了から振り返りを開くまで（[RequestReviewForFinishedWalkUseCase]）。
 *
 * いちばん見たいのは**放置セッションで開かないこと**：`abandonOpenSessions` は
 * 散歩を始めた瞬間に走るので、弾かないと「出発ボタンを押したら前回の振り返りが開く」になる。
 */
class RequestReviewForFinishedWalkUseCaseTest {

    private val sessions = FakeWalkSessionRepository()
    private val pending = InMemoryPendingReviewRepository()
    private val useCase = RequestReviewForFinishedWalkUseCase(sessions, pending)

    @Test
    fun 自動終了した散歩は振り返りを開く() = runTest {
        val sessionId = sessions.startSession(1_000L)
        sessions.endSession(sessionId, 2_000L, SessionEndReason.AUTO_ARRIVAL)

        assertTrue(useCase(sessionId))
        assertEquals(sessionId, pending.pending.first())
    }

    @Test
    fun 手動で終えた散歩も開く() = runTest {
        val sessionId = sessions.startSession(1_000L)
        sessions.endSession(sessionId, 2_000L, SessionEndReason.MANUAL)

        assertTrue(useCase(sessionId))
    }

    @Test
    fun 放置セッションでは開かない() = runTest {
        sessions.startSession(1_000L)
        val abandoned = sessions.abandonOpenSessions().single()

        assertFalse(useCase(abandoned))
        assertNull(pending.pending.first(), "散歩開始直後に前回の振り返りが開く事故を防ぐ")
    }

    @Test
    fun 消費すると合図が消える() = runTest {
        val sessionId = sessions.startSession(1_000L)
        sessions.endSession(sessionId, 2_000L, SessionEndReason.AUTO_ARRIVAL)
        useCase(sessionId)

        pending.consume()

        assertNull(pending.pending.first())
    }

    @Test
    fun 知らないセッションは開かない() = runTest {
        assertFalse(useCase(sessionId = 999L))
        assertNull(pending.pending.first())
    }
}

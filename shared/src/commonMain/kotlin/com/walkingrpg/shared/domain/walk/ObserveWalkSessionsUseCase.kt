package com.walkingrpg.shared.domain.walk

import kotlinx.coroutines.flow.Flow

/** 記録済みセッションの一覧（新しい順）を購読する。 */
class ObserveWalkSessionsUseCase(
    private val repository: WalkSessionRepository,
) {
    operator fun invoke(): Flow<List<WalkSessionSummary>> = repository.observeSessions()
}

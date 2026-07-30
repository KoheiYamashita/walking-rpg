package com.walkingrpg.shared.domain.weather

import kotlinx.coroutines.flow.Flow

/**
 * 1セッションの天候を購読する（issue #15 の遅延差し込み）。
 *
 * 振り返りを開いた時点では天候の後付け取得（[FetchMissingSessionWeatherUseCase]）が
 * まだ終わっていないことがある。数値サマリを待たせずに先に出し、取れたら1行増やすための口
 * （architecture.md §5「数値は即時、文章は遅延OK」）。
 */
class ObserveSessionWeatherUseCase(
    private val repository: SessionWeatherRepository,
) {
    operator fun invoke(sessionId: Long): Flow<SessionWeather?> = repository.observe(sessionId)
}

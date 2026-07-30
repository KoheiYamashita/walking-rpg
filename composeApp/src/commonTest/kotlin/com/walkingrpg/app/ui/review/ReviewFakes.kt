package com.walkingrpg.app.ui.review

import com.walkingrpg.shared.domain.codex.CodexProgress
import com.walkingrpg.shared.domain.codex.CodexProgressRepository
import com.walkingrpg.shared.domain.codex.RecentCodexRepository
import com.walkingrpg.shared.domain.llm.LlmCacheEntry
import com.walkingrpg.shared.domain.llm.LlmCacheRepository
import com.walkingrpg.shared.domain.llm.LlmTaskKind
import com.walkingrpg.shared.domain.matching.Passage
import com.walkingrpg.shared.domain.matching.PassageRepository
import com.walkingrpg.shared.domain.matching.SessionVisit
import com.walkingrpg.shared.domain.osm.OsmMasterCounts
import com.walkingrpg.shared.domain.osm.OsmMasterRepository
import com.walkingrpg.shared.domain.osm.Poi
import com.walkingrpg.shared.domain.osm.Way
import com.walkingrpg.shared.domain.walk.LocationSample
import com.walkingrpg.shared.domain.walk.SessionEndReason
import com.walkingrpg.shared.domain.walk.WalkSession
import com.walkingrpg.shared.domain.walk.WalkSessionRepository
import com.walkingrpg.shared.domain.walk.WalkSessionSummary
import com.walkingrpg.shared.domain.weather.SessionWeather
import com.walkingrpg.shared.domain.weather.SessionWeatherRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map

/**
 * 振り返り画面のテスト用差し替え。
 *
 * shared 側のフェイク（`FakeDerivationRepositories` 等）は shared のテストソースセットに
 * 閉じているので、UI層のテストには**必要な口だけ**を持つ最小の実装を置く。
 * 振り返りの中身の正しさは shared 側（`GetWalkReviewUseCaseTest`）が見ているので、
 * ここでは「数値が出て、あとから文章と天候が届く」ことだけを作れれば足りる。
 *
 * 保存で購読が流れる形（[MutableStateFlow]）にしてあるのは、実装（SQLDelightの
 * `asFlow()`）が書き込みのたびにクエリを流し直すのと同じ動きにするため。
 */

/** セッション1件だけを持つ [WalkSessionRepository]。 */
internal class SingleSessionRepository(
    private val session: WalkSession?,
) : WalkSessionRepository {

    override suspend fun session(sessionId: Long): WalkSession? =
        session?.takeIf { it.id == sessionId }

    override suspend fun startSession(startedAtMs: Long): Long = notUsed()
    override suspend fun appendSample(sample: LocationSample) = notUsed()
    override suspend fun endSession(sessionId: Long, endedAtMs: Long, reason: SessionEndReason) =
        notUsed()

    override suspend fun abandonOpenSessions(): List<Long> = notUsed()
    override fun observeSessions(): Flow<List<WalkSessionSummary>> = emptyFlow()
    override suspend fun samples(sessionId: Long): List<LocationSample> = emptyList()
    override suspend fun recentFinishedSessions(limit: Int): List<WalkSession> = listOfNotNull(session)

    private fun notUsed(): Nothing = error("振り返り画面では使わない")
}

/** 通過を直接置ける [PassageRepository]。 */
internal class StubPassageRepository(
    private val sessionPassages: Map<Long, List<Passage>> = emptyMap(),
    private val passCounts: Map<Long, Int> = emptyMap(),
) : PassageRepository {

    override suspend fun replaceSessionPassages(sessionId: Long, passages: List<Passage>) = Unit
    override suspend fun passages(sessionId: Long): List<Passage> =
        sessionPassages[sessionId].orEmpty()

    override suspend fun passCountsByWay(): Map<Long, Int> = passCounts
    override suspend fun sessionVisitsByWay(): Map<Long, List<SessionVisit>> = emptyMap()
}

/** wayマスタだけを持つ [OsmMasterRepository]。 */
internal class StubOsmMasterRepository(
    private val ways: List<Way> = emptyList(),
) : OsmMasterRepository {

    override suspend fun save(ways: List<Way>, pois: List<Poi>) = Unit
    override suspend fun counts(): OsmMasterCounts = OsmMasterCounts(ways.size, 0)
    override suspend fun ways(): List<Way> = ways
    override suspend fun pois(): List<Poi> = emptyList()
}

/** `llm_cache` のインメモリ版（保存で購読が流れる）。 */
internal class FakeLlmCacheRepository : LlmCacheRepository {

    private val rows = MutableStateFlow<Map<String, LlmCacheEntry>>(emptyMap())

    override suspend fun entry(cacheKey: String): LlmCacheEntry? = rows.value[cacheKey]

    override fun observe(cacheKey: String): Flow<LlmCacheEntry?> = rows.map { it[cacheKey] }

    override suspend fun entries(kind: LlmTaskKind): Map<String, LlmCacheEntry> =
        rows.value.filterValues { it.kind == kind }

    override suspend fun save(entry: LlmCacheEntry) {
        rows.value = rows.value + (entry.cacheKey to entry)
    }
}

/** `session_weather` のインメモリ版。行が無い＝未取得。 */
internal class FakeSessionWeatherRepository : SessionWeatherRepository {

    private val rows = MutableStateFlow<Map<Long, SessionWeather>>(emptyMap())

    override suspend fun save(weather: SessionWeather) {
        rows.value = rows.value + (weather.sessionId to weather)
    }

    override suspend fun weather(sessionId: Long): SessionWeather? = rows.value[sessionId]

    override fun observe(sessionId: Long): Flow<SessionWeather?> = rows.map { it[sessionId] }

    override suspend fun sessionIdsWithoutWeather(): List<Long> = emptyList()
}

/** 進捗が空の [CodexProgressRepository]（図鑑の総数だけがカタログから出る）。 */
internal class EmptyCodexProgressRepository : CodexProgressRepository {
    override suspend fun replaceAllProgresses(progresses: List<CodexProgress>) = Unit
    override suspend fun progresses(): List<CodexProgress> = emptyList()
    override suspend fun progress(speciesId: String): CodexProgress? = null
}

/** 余韻の強調を持たない [RecentCodexRepository]。 */
internal class EmptyRecentCodexRepository : RecentCodexRepository {
    override val discoveredSpeciesIds: Set<String> = emptySet()
    override val updates: Flow<Set<String>> = emptyFlow()
    override fun record(speciesIds: Set<String>) = Unit
}

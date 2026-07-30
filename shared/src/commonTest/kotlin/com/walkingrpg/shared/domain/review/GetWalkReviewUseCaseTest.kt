package com.walkingrpg.shared.domain.review

import com.walkingrpg.shared.data.FakeWalkSessionRepository
import com.walkingrpg.shared.domain.FakeCodexProgressRepository
import com.walkingrpg.shared.domain.FakeOsmMasterRepository
import com.walkingrpg.shared.domain.FakePassageRepository
import com.walkingrpg.shared.domain.FakeRecentCodexRepository
import com.walkingrpg.shared.domain.codex.CodexCategory
import com.walkingrpg.shared.domain.codex.GetCodexUseCase
import com.walkingrpg.shared.domain.codex.Species
import com.walkingrpg.shared.domain.codex.codexPoi
import com.walkingrpg.shared.domain.codex.testSpecies
import com.walkingrpg.shared.domain.growth.GrowthStage
import com.walkingrpg.shared.domain.llm.FakeLlmCacheRepository
import com.walkingrpg.shared.domain.llm.GetSpeciesDescriptionUseCase
import com.walkingrpg.shared.domain.matching.Passage
import com.walkingrpg.shared.domain.matching.SessionVisit
import com.walkingrpg.shared.domain.matching.SyntheticWalk
import com.walkingrpg.shared.domain.osm.PoiKind
import com.walkingrpg.shared.domain.osm.Way
import com.walkingrpg.shared.domain.walk.SessionEndReason
import com.walkingrpg.shared.domain.weather.FakeSessionWeatherRepository
import com.walkingrpg.shared.domain.weather.SessionWeather
import com.walkingrpg.shared.domain.weather.WeatherCondition
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 振り返りの組み立て（[GetWalkReviewUseCase]）。
 *
 * 見たいのは4つ：
 * - 数値サマリが確定データから出る（**通信しない**＝LLMもプロバイダも渡していない）
 * - データが欠けていても成立する（天候なし・図鑑なし・マスタなし）
 * - 同じセッションを何度組んでも同じ結果（冪等）
 * - そのセッションの通過が押し上げた道だけが「今日育った道」になる
 */
class GetWalkReviewUseCaseTest {

    private val waterSpecies = testSpecies("water", CodexCategory.WATER, requiredVisitCount = 3)

    private class Fixture {
        val sessions = FakeWalkSessionRepository()
        val passages = FakePassageRepository()
        val osm = FakeOsmMasterRepository()
        val weather = FakeSessionWeatherRepository()
        val codexProgress = FakeCodexProgressRepository()
        val recentCodex = FakeRecentCodexRepository()
        val llmCache = FakeLlmCacheRepository()
    }

    private fun useCase(fixture: Fixture, catalog: List<Species>) = GetWalkReviewUseCase(
        walkSessionRepository = fixture.sessions,
        passageRepository = fixture.passages,
        osmMasterRepository = fixture.osm,
        sessionWeatherRepository = fixture.weather,
        getCodex = GetCodexUseCase(
            codexProgressRepository = fixture.codexProgress,
            getSpeciesDescription = GetSpeciesDescriptionUseCase(fixture.llmCache),
            recentCodexRepository = fixture.recentCodex,
            catalog = catalog,
        ),
        catalog = catalog,
    )

    private fun way(id: Long, lengthMeters: Double, name: String? = null): Way = Way(
        id = id,
        name = name,
        highway = "residential",
        geometry = emptyList(),
        lengthMeters = lengthMeters,
    )

    private fun passage(sessionId: Long, wayId: Long, minute: Long) = Passage(
        sessionId = sessionId,
        wayId = wayId,
        timestampMs = SyntheticWalk.START_MS + minute * 60_000L,
    )

    /** 30分・2.3kmの散歩1回を仕込む（design.md §3 のウォークスルーの数字）。 */
    private suspend fun tuesdayWalk(fixture: Fixture): Long {
        val sessionId = fixture.sessions.startSession(SyntheticWalk.START_MS)
        fixture.sessions.endSession(
            sessionId = sessionId,
            endedAtMs = SyntheticWalk.START_MS + 30 * 60_000L,
            reason = SessionEndReason.AUTO_ARRIVAL,
        )
        fixture.osm.save(
            ways = listOf(way(1L, 800.0, name = "けやき通り"), way(2L, 1_500.0)),
            pois = emptyList(),
        )
        fixture.passages.sessionPassages = mapOf(
            sessionId to listOf(passage(sessionId, 1L, 2), passage(sessionId, 2L, 12)),
        )
        return sessionId
    }

    @Test
    fun 数値サマリが確定データから出る() = runTest {
        val fixture = Fixture()
        val sessionId = tuesdayWalk(fixture)
        // 1本目は8回目の通過＝低木に上がる。2本目は初回
        fixture.passages.passCounts = mapOf(1L to 8, 2L to 1)

        val review = checkNotNull(useCase(fixture, listOf(waterSpecies)).invoke(sessionId))

        assertEquals(2_300.0, review.distanceMeters, "今日の道＝通った道の長さの合算")
        assertEquals(30 * 60_000L, review.durationMs)
        assertEquals(
            listOf("けやき通り", null),
            review.grownWays.map { it.name },
            "育った道は名前つきで出る（無名は null のままUI層が言い換える）",
        )
        assertEquals(GrowthStage.FLOWER, review.grownWays.first().from)
        assertEquals(GrowthStage.SHRUB, review.grownWays.first().to)
        assertNull(review.grownWays.last().from, "はじめて通った道は未踏から")
    }

    @Test
    fun 天候がまだ無くても成立する() = runTest {
        val fixture = Fixture()
        val sessionId = tuesdayWalk(fixture)
        fixture.passages.passCounts = mapOf(1L to 1, 2L to 1)

        val review = checkNotNull(useCase(fixture, listOf(waterSpecies)).invoke(sessionId))

        assertNull(review.weather, "後付け取得が済んでいない＝行が無い")
        assertEquals(2_300.0, review.distanceMeters, "数値サマリは天候を待たない")
    }

    @Test
    fun 天候が取れていれば載る() = runTest {
        val fixture = Fixture()
        val sessionId = tuesdayWalk(fixture)
        fixture.weather.save(
            SessionWeather(sessionId, WeatherCondition.CLOUDY, 18.0, fetchedAtMs = 1L),
        )

        val review = checkNotNull(useCase(fixture, listOf(waterSpecies)).invoke(sessionId))

        assertEquals(WeatherCondition.CLOUDY, review.weather?.condition)
    }

    @Test
    fun 通過が1件も無い散歩でも組める() = runTest {
        // 圏外どころか測位に失敗した散歩（サンプル0件）。0kmの振り返りが出るだけ
        val fixture = Fixture()
        val sessionId = fixture.sessions.startSession(SyntheticWalk.START_MS)
        fixture.sessions.endSession(sessionId, SyntheticWalk.START_MS + 60_000L, SessionEndReason.MANUAL)

        val review = checkNotNull(useCase(fixture, listOf(waterSpecies)).invoke(sessionId))

        assertEquals(0.0, review.distanceMeters)
        assertEquals(emptyList(), review.grownWays)
        assertEquals(emptyList(), review.discoveredSpecies)
        assertEquals(emptyList(), review.approachedSpecies)
        assertEquals(0, review.codexDiscoveredCount)
        assertEquals(1, review.codexTotalCount, "図鑑の総数はカタログの件数（進捗が無くても出る）")
    }

    @Test
    fun 存在しないセッションはnull() = runTest {
        val fixture = Fixture()

        assertNull(useCase(fixture, listOf(waterSpecies)).invoke(sessionId = 999L))
    }

    @Test
    fun 何度組んでも同じ結果になる() = runTest {
        val fixture = Fixture()
        val sessionId = tuesdayWalk(fixture)
        fixture.passages.passCounts = mapOf(1L to 8, 2L to 1)
        val useCase = useCase(fixture, listOf(waterSpecies))

        assertEquals(useCase(sessionId), useCase(sessionId), "副作用が無い＝余韻の記録とは別物")
    }

    @Test
    fun 他の散歩で育った道は入らない() = runTest {
        val fixture = Fixture()
        val sessionId = tuesdayWalk(fixture)
        // 3本目の道は他の散歩だけで低木に届いている
        fixture.osm.save(
            ways = listOf(way(1L, 800.0), way(2L, 1_500.0), way(3L, 300.0)),
            pois = emptyList(),
        )
        fixture.passages.passCounts = mapOf(1L to 8, 2L to 1, 3L to 8)

        val review = checkNotNull(useCase(fixture, listOf(waterSpecies)).invoke(sessionId))

        assertEquals(listOf(1L, 2L), review.grownWays.map { it.wayId })
    }

    @Test
    fun そのセッションで近づいた種が予兆として出る() = runTest {
        val fixture = Fixture()
        val sessionId = tuesdayWalk(fixture)
        fixture.osm.save(
            ways = listOf(
                SyntheticWalk.eastWestWay(id = 1L, northMeters = 10.0, fromEast = -50.0, toEast = 50.0),
            ),
            pois = listOf(codexPoi("node/1", PoiKind.WATER)),
        )
        fixture.passages.sessionPassages = mapOf(sessionId to listOf(passage(sessionId, 1L, 2)))
        fixture.passages.passCounts = mapOf(1L to 1)
        // この散歩が水辺POIの初訪問（3回で出る種なので、2回手前＝いま予兆が立つ）
        fixture.passages.sessionVisits = mapOf(
            1L to listOf(SessionVisit(sessionId = sessionId, firstTimestampMs = SyntheticWalk.START_MS)),
        )

        val review = checkNotNull(useCase(fixture, listOf(waterSpecies)).invoke(sessionId))

        assertEquals(listOf(waterSpecies), review.approachedSpecies)
        assertTrue(review.discoveredSpecies.isEmpty(), "まだ出会っていない")
    }
}

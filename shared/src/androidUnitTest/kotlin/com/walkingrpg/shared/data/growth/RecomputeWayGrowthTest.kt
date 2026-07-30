package com.walkingrpg.shared.data.growth

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.walkingrpg.shared.data.WalkSessionRepositoryImpl
import com.walkingrpg.shared.data.codex.CodexProgressRepositoryImpl
import com.walkingrpg.shared.data.codex.InMemoryRecentCodexRepository
import com.walkingrpg.shared.data.db.WalkingRpgDatabase
import com.walkingrpg.shared.data.matching.PassageRepositoryImpl
import com.walkingrpg.shared.data.osm.OsmMasterRepositoryImpl
import com.walkingrpg.shared.domain.codex.RecomputeCodexProgressUseCase
import com.walkingrpg.shared.domain.growth.GrowthConfig
import com.walkingrpg.shared.domain.growth.GrowthStage
import com.walkingrpg.shared.domain.growth.RecomputeAfterWalkUseCase
import com.walkingrpg.shared.domain.growth.RecomputeWayGrowthUseCase
import com.walkingrpg.shared.domain.matching.MapMatchingConfig
import com.walkingrpg.shared.domain.matching.RecomputePassagesUseCase
import com.walkingrpg.shared.domain.matching.SyntheticWalk
import com.walkingrpg.shared.domain.osm.Way
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `WayGrowth.sq` と成長の再計算を実際のSQLite（インメモリ）で検証する。
 *
 * 見たいのは issue #9 の完了条件そのもの：
 * 「散歩するたびに pass_count が増えて段階が上がる」ことと、
 * 「導出テーブルを消して再計算しても同じ結果になる」こと（冪等性。architecture.md §7）。
 * 座標はすべて架空（[SyntheticWalk]）。
 */
class RecomputeWayGrowthTest {

    private val mainStreet: Way =
        SyntheticWalk.eastWestWay(id = 1L, northMeters = 0.0, fromEast = 0.0, toEast = 300.0)

    // main street の東端に接続する南北の道（「その日は途中で曲がった」散歩用）
    private val crossStreet: Way =
        SyntheticWalk.northSouthWay(id = 2L, eastMeters = 300.0, fromNorth = 0.0, toNorth = 300.0)

    private class Fixture(driver: JdbcSqliteDriver) {
        val database = WalkingRpgDatabase(driver)
        val sessions = WalkSessionRepositoryImpl(database)
        val master = OsmMasterRepositoryImpl(database)
        val passages = PassageRepositoryImpl(database)
        val growths = WayGrowthRepositoryImpl(database)
        val recentGrowth = InMemoryRecentGrowthRepository()
        val codex = CodexProgressRepositoryImpl(database)
        val recentCodex = InMemoryRecentCodexRepository()

        val recomputeGrowth = RecomputeWayGrowthUseCase(
            passageRepository = passages,
            wayGrowthRepository = growths,
            config = GrowthConfig.DEFAULT,
        )

        fun afterWalk(config: MapMatchingConfig = MapMatchingConfig.DEFAULT) = RecomputeAfterWalkUseCase(
            recomputePassages = RecomputePassagesUseCase(
                sessionRepository = sessions,
                osmMasterRepository = master,
                passageRepository = passages,
                config = config,
            ),
            recomputeWayGrowth = recomputeGrowth,
            recomputeCodexProgress = RecomputeCodexProgressUseCase(
                passageRepository = passages,
                osmMasterRepository = master,
                codexProgressRepository = codex,
            ),
            wayGrowthRepository = growths,
            codexProgressRepository = codex,
            recentGrowthRepository = recentGrowth,
            recentCodexRepository = recentCodex,
        )
    }

    private suspend fun fixture(ways: List<Way>): Fixture {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        WalkingRpgDatabase.Schema.create(driver)
        return Fixture(driver).also { fixture ->
            fixture.master.save(ways = ways, pois = emptyList())
        }
    }

    /** 本通りを東へ300m歩いて帰宅する（1回の散歩＝mainStreetの通過1回）。 */
    private suspend fun walkMainStreet(fixture: Fixture, accuracyMeters: Double = SyntheticWalk.GOOD_ACCURACY_M): Long {
        val sessionId = fixture.sessions.startSession(startedAtMs = SyntheticWalk.START_MS)
        SyntheticWalk.samples(
            points = List(30) { index -> SyntheticWalk.point(0.0, 20.0 + index * 2.8) },
            accuracyMeters = accuracyMeters,
        ).forEach { sample -> fixture.sessions.appendSample(sample.copy(sessionId = sessionId)) }
        return sessionId
    }

    /** 本通りを東へ行き、突き当たりを北へ曲がる（mainStreet と crossStreet を1回ずつ）。 */
    private suspend fun walkAndTurnNorth(fixture: Fixture): Long {
        val sessionId = fixture.sessions.startSession(startedAtMs = SyntheticWalk.START_MS)
        val eastward = List(60) { index -> SyntheticWalk.point(0.0, 130.0 + index * 2.8) }
        val northward = List(30) { index -> SyntheticWalk.point(5.0 + index * 2.8, 300.0) }
        SyntheticWalk.samples(points = eastward + northward)
            .forEach { sample -> fixture.sessions.appendSample(sample.copy(sessionId = sessionId)) }
        return sessionId
    }

    @Test
    fun 散歩するたびに通った道のpass_countが増えて段階が上がる() = runTest {
        val fixture = fixture(listOf(mainStreet))
        val afterWalk = fixture.afterWalk()
        val config = GrowthConfig.DEFAULT

        val afterEachWalk = (1..config.flowerPassCount).map {
            afterWalk(walkMainStreet(fixture))
            fixture.growths.growth(mainStreet.id)
        }

        assertEquals(
            (1..config.flowerPassCount).toList(),
            afterEachWalk.map { it?.passCount },
            "散歩1回につき通過1回ぶん増える",
        )
        assertEquals(GrowthStage.GRASS, afterEachWalk.first()?.stage)
        assertEquals(GrowthStage.FLOWER, afterEachWalk.last()?.stage, "閾値に届いた時点で段階が上がる")
    }

    @Test
    fun 導出テーブルを消して再計算しても同じ結果になる() = runTest {
        val fixture = fixture(listOf(mainStreet, crossStreet))
        val afterWalk = fixture.afterWalk()
        repeat(3) { afterWalk(walkMainStreet(fixture)) }
        afterWalk(walkAndTurnNorth(fixture))
        val before = fixture.growths.growths()

        // way_growth を丸ごと捨てる（＝キャッシュを消す）。真実は passage 側に残っている。
        fixture.database.wayGrowthQueries.deleteAllWayGrowth()
        assertTrue(fixture.growths.growths().isEmpty())

        assertEquals(before, fixture.recomputeGrowth())
        assertEquals(before, fixture.growths.growths())
        assertEquals(
            listOf(4 to GrowthStage.FLOWER, 1 to GrowthStage.GRASS),
            before.map { it.passCount to it.stage },
            "本通り4回・曲がった先1回",
        )
    }

    @Test
    fun 再計算を2回流しても成長が増えない() = runTest {
        val fixture = fixture(listOf(mainStreet))
        val afterWalk = fixture.afterWalk()
        afterWalk(walkMainStreet(fixture))

        val first = fixture.recomputeGrowth()
        val second = fixture.recomputeGrowth()

        assertEquals(first, second)
        assertEquals(first, fixture.growths.growths())
        assertEquals(1L, fixture.database.wayGrowthQueries.countWayGrowth().executeAsOne())
    }

    @Test
    fun 通過が消えた道は成長も消える() = runTest {
        val fixture = fixture(listOf(mainStreet))
        val sessionId = walkMainStreet(fixture, accuracyMeters = 20.0)
        assertTrue(fixture.afterWalk().invoke(sessionId).growths.isNotEmpty())

        // 精度10mまでしか信じない設定で作り直すと passage が消える → 成長も消える
        fixture.afterWalk(MapMatchingConfig(maxAccuracyMeters = 10.0)).invoke(sessionId)

        assertTrue(fixture.growths.growths().isEmpty())
        assertNull(fixture.growths.growth(mainStreet.id))
    }

    @Test
    fun 散歩のたびに段階が上がった道が地図の強調に渡る() = runTest {
        val fixture = fixture(listOf(mainStreet, crossStreet))
        val afterWalk = fixture.afterWalk()

        // 1回目：本通りに草が生える
        val first = afterWalk(walkMainStreet(fixture))
        assertEquals(setOf(mainStreet.id), first.stageRaisedWayIds)
        assertEquals(setOf(mainStreet.id), fixture.recentGrowth.stageRaisedWayIds)

        // 2回目：曲がった先が初踏破。本通りは通過2回目で段階は据え置き
        val second = afterWalk(walkAndTurnNorth(fixture))
        assertEquals(setOf(crossStreet.id), second.stageRaisedWayIds)
        assertEquals(
            setOf(crossStreet.id),
            fixture.recentGrowth.stageRaisedWayIds,
            "前回の散歩の強調は次の再計算で消える",
        )
    }

    @Test
    fun 一度も歩いていない道には行がない() = runTest {
        val fixture = fixture(listOf(mainStreet, crossStreet))

        fixture.afterWalk().invoke(walkMainStreet(fixture))

        assertNull(fixture.growths.growth(crossStreet.id), "wayマスタにあっても未踏なら成長は無い")
        assertEquals(listOf(mainStreet.id), fixture.growths.growths().map { it.wayId })
    }
}

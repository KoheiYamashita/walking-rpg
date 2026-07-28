package com.walkingrpg.shared.data.matching

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.walkingrpg.shared.data.WalkSessionRepositoryImpl
import com.walkingrpg.shared.data.db.WalkingRpgDatabase
import com.walkingrpg.shared.data.osm.OsmMasterRepositoryImpl
import com.walkingrpg.shared.domain.matching.MapMatchingConfig
import com.walkingrpg.shared.domain.matching.Passage
import com.walkingrpg.shared.domain.matching.RecomputePassagesUseCase
import com.walkingrpg.shared.domain.matching.SyntheticWalk
import com.walkingrpg.shared.domain.osm.Way
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `Passage.sq` と再計算の結線を実際のSQLite（インメモリ）で検証する。
 * 見たいのは「セッション単位で作り直すか」＝何度流しても行が増えず、
 * 閾値を変えたときに前回の結果が残らないこと。座標はすべて架空。
 */
class RecomputePassagesTest {

    private val mainStreet: Way =
        SyntheticWalk.eastWestWay(id = 1L, northMeters = 0.0, fromEast = 0.0, toEast = 300.0)

    private class Fixture(driver: JdbcSqliteDriver) {
        val database = WalkingRpgDatabase(driver)
        val sessions = WalkSessionRepositoryImpl(database)
        val master = OsmMasterRepositoryImpl(database)
        val passages = PassageRepositoryImpl(database)

        fun useCase(config: MapMatchingConfig) = RecomputePassagesUseCase(
            sessionRepository = sessions,
            osmMasterRepository = master,
            passageRepository = passages,
            config = config,
        )
    }

    private fun fixture(): Fixture {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        WalkingRpgDatabase.Schema.create(driver)
        return Fixture(driver)
    }

    /** 本通りを東へ歩いたサンプルを積む。 */
    private suspend fun recordWalk(fixture: Fixture, accuracyMeters: Double): Long {
        val sessionId = fixture.sessions.startSession(startedAtMs = SyntheticWalk.START_MS)
        SyntheticWalk.samples(
            points = List(30) { index -> SyntheticWalk.point(0.0, 20.0 + index * 2.8) },
            accuracyMeters = accuracyMeters,
        ).forEach { sample ->
            fixture.sessions.appendSample(sample.copy(sessionId = sessionId))
        }
        return sessionId
    }

    @Test
    fun 再計算を2回流しても通過が増えない() = runTest {
        val fixture = fixture()
        fixture.master.save(ways = listOf(mainStreet), pois = emptyList())
        val sessionId = recordWalk(fixture, accuracyMeters = SyntheticWalk.GOOD_ACCURACY_M)
        val useCase = fixture.useCase(MapMatchingConfig.DEFAULT)

        val first = useCase(sessionId)
        val second = useCase(sessionId)

        assertEquals(
            listOf(Passage(sessionId, wayId = 1L, timestampMs = SyntheticWalk.START_MS)),
            first,
        )
        assertEquals(first, second)
        assertEquals(first, fixture.passages.passages(sessionId))
    }

    @Test
    fun 閾値を厳しくして再計算すると前回の通過が残らない() = runTest {
        val fixture = fixture()
        fixture.master.save(ways = listOf(mainStreet), pois = emptyList())
        val sessionId = recordWalk(fixture, accuracyMeters = 20.0)

        assertTrue(fixture.useCase(MapMatchingConfig.DEFAULT).invoke(sessionId).isNotEmpty())

        // 精度10mまでしか信じない設定にすると、このセッションのサンプルは全部落ちる
        val strict = MapMatchingConfig(maxAccuracyMeters = 10.0)
        assertTrue(fixture.useCase(strict).invoke(sessionId).isEmpty())

        assertTrue(fixture.passages.passages(sessionId).isEmpty())
    }

    @Test
    fun 通過は他のセッションに影響しない() = runTest {
        val fixture = fixture()
        fixture.master.save(ways = listOf(mainStreet), pois = emptyList())
        val first = recordWalk(fixture, accuracyMeters = SyntheticWalk.GOOD_ACCURACY_M)
        val second = recordWalk(fixture, accuracyMeters = SyntheticWalk.GOOD_ACCURACY_M)
        val useCase = fixture.useCase(MapMatchingConfig.DEFAULT)

        useCase(first)
        useCase(second)
        // 1つ目だけを作り直しても2つ目は消えない
        useCase(first)

        assertEquals(1, fixture.passages.passages(first).size)
        assertEquals(1, fixture.passages.passages(second).size)
        assertEquals(second, fixture.passages.passages(second).single().sessionId)
    }

    @Test
    fun wayマスタが空なら通過は作られない() = runTest {
        val fixture = fixture()
        val sessionId = recordWalk(fixture, accuracyMeters = SyntheticWalk.GOOD_ACCURACY_M)

        val result = fixture.useCase(MapMatchingConfig.DEFAULT).invoke(sessionId)

        assertTrue(result.isEmpty())
        assertTrue(fixture.passages.passages(sessionId).isEmpty())
    }
}

package com.walkingrpg.app.ui.map

import com.walkingrpg.shared.domain.growth.GrowthStage
import com.walkingrpg.shared.domain.growth.ObserveGrowthUpdatesUseCase
import com.walkingrpg.shared.domain.growth.RecentGrowthRepository
import com.walkingrpg.shared.domain.growth.WayGrowth
import com.walkingrpg.shared.domain.growth.WayGrowthRepository
import com.walkingrpg.shared.domain.map.GeoPoint
import com.walkingrpg.shared.domain.map.GetMapSceneUseCase
import com.walkingrpg.shared.domain.osm.OsmMasterCounts
import com.walkingrpg.shared.domain.osm.OsmMasterRepository
import com.walkingrpg.shared.domain.osm.Poi
import com.walkingrpg.shared.domain.osm.Way
import com.walkingrpg.shared.domain.walk.CurrentLocationRepository
import com.walkingrpg.shared.domain.walk.LocationFix
import com.walkingrpg.shared.domain.walk.ObserveIsWalkingUseCase
import com.walkingrpg.shared.domain.walk.WalkRecorder
import com.walkingrpg.shared.domain.walk.WalkRecordingState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 地図画面の状態の組み立て（issue #10）。
 *
 * 見たいのは2つ：
 * - 記録中だけ現在地に追従すること
 * - 散歩から帰って成長が作り直されたら、開いたままでも色が入れ替わること
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MapViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        // viewModelScope は Dispatchers.Main を使うのでテスト用に差し替える
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FakeCurrentLocationRepository : CurrentLocationRepository {
        override suspend fun currentFix(): LocationFix? = null
    }

    private class FakeOsmMasterRepository(private val ways: List<Way>) : OsmMasterRepository {
        override suspend fun save(ways: List<Way>, pois: List<Poi>) = Unit
        override suspend fun counts() = OsmMasterCounts(wayCount = ways.size, poiCount = 0)
        override suspend fun ways(): List<Way> = ways
        override suspend fun pois(): List<Poi> = emptyList()
    }

    private class FakeWayGrowthRepository : WayGrowthRepository {
        var growths: List<WayGrowth> = emptyList()

        override suspend fun replaceAllGrowths(growths: List<WayGrowth>) {
            this.growths = growths
        }

        override suspend fun growths(): List<WayGrowth> = growths
        override suspend fun growth(wayId: Long): WayGrowth? =
            growths.firstOrNull { it.wayId == wayId }
    }

    private class FakeRecentGrowthRepository : RecentGrowthRepository {
        private val _stageRaisedWayIds = MutableStateFlow<Set<Long>>(emptySet())
        override val stageRaisedWayIds: StateFlow<Set<Long>> = _stageRaisedWayIds.asStateFlow()

        override fun record(wayIds: Set<Long>) {
            _stageRaisedWayIds.value = wayIds
        }
    }

    /** 記録中かどうかだけを差し替えられる [WalkRecorder]。 */
    private class FakeWalkRecorder : WalkRecorder {
        private val _state = MutableStateFlow(WalkRecordingState())
        override val state: StateFlow<WalkRecordingState> = _state.asStateFlow()
        override val finishedSessions: Flow<Long> = emptyFlow()

        override suspend fun start() {
            _state.value = WalkRecordingState().started(sessionId = 1L, startedAtMs = 0L)
        }

        override suspend fun stop() {
            _state.value = _state.value.stopped(stoppedAtMs = 0L)
        }
    }

    private val way = Way(
        id = 1L,
        name = null,
        highway = "residential",
        geometry = listOf(GeoPoint(35.0, 139.0), GeoPoint(35.001, 139.0)),
        lengthMeters = 100.0,
    )

    private class Fixture {
        val recorder = FakeWalkRecorder()
        val growths = FakeWayGrowthRepository()
        val recentGrowth = FakeRecentGrowthRepository()
    }

    private fun viewModel(fixture: Fixture, ways: List<Way>) = MapViewModel(
        getMapScene = GetMapSceneUseCase(
            currentLocationRepository = FakeCurrentLocationRepository(),
            wayGrowthRepository = fixture.growths,
            osmMasterRepository = FakeOsmMasterRepository(ways),
            recentGrowthRepository = fixture.recentGrowth,
        ),
        observeIsWalking = ObserveIsWalkingUseCase(fixture.recorder),
        observeGrowthUpdates = ObserveGrowthUpdatesUseCase(fixture.recentGrowth),
    )

    @Test
    fun 記録中だけ現在地に追従する() = runTest(dispatcher) {
        val fixture = Fixture()
        val viewModel = viewModel(fixture, ways = listOf(way))
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isFollowingUser)

        fixture.recorder.start()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isFollowingUser)

        fixture.recorder.stop()
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isFollowingUser)
    }

    @Test
    fun 散歩後の再計算で地図を開いたまま色が入れ替わる() = runTest(dispatcher) {
        val fixture = Fixture()
        val viewModel = viewModel(fixture, ways = listOf(way))
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isLoading)
        assertEquals(emptyList(), viewModel.uiState.value.highlights)

        // 散歩から帰ってきた（RecomputeAfterWalkUseCase 相当）
        fixture.growths.growths = listOf(
            WayGrowth(wayId = way.id, passCount = 1, stage = GrowthStage.GRASS),
        )
        fixture.recentGrowth.record(setOf(way.id))
        advanceUntilIdle()

        val highlight = viewModel.uiState.value.highlights.single()
        assertEquals(way.id, highlight.wayId)
        assertEquals(GrowthStage.GRASS, highlight.stage)
        assertTrue(highlight.isNewlyGrown, "今回育った道は強調される")
    }
}

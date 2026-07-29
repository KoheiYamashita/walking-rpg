package com.walkingrpg.shared.domain.growth

import com.walkingrpg.shared.data.FakeWalkSessionRepository
import com.walkingrpg.shared.domain.FakeOsmMasterRepository
import com.walkingrpg.shared.domain.FakePassageRepository
import com.walkingrpg.shared.domain.FakeRecentGrowthRepository
import com.walkingrpg.shared.domain.FakeWayGrowthRepository
import com.walkingrpg.shared.domain.matching.RecomputePassagesUseCase
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 散歩1回ぶんの作り直しが「段階が上がった道」まで出すこと（issue #10）。
 *
 * map matching 本体（[RecomputePassagesUseCase] の中身）はここでは見ない。
 * 通過の回数は [FakePassageRepository] に直接置いて、
 * 「作り直しの前後を比べて差分が出るか」だけを見る。
 */
class RecomputeAfterWalkUseCaseTest {

    private val passages = FakePassageRepository()
    private val growths = FakeWayGrowthRepository()
    private val recentGrowth = FakeRecentGrowthRepository()

    private val useCase = RecomputeAfterWalkUseCase(
        recomputePassages = RecomputePassagesUseCase(
            sessionRepository = FakeWalkSessionRepository(),
            osmMasterRepository = FakeOsmMasterRepository(),
            passageRepository = passages,
        ),
        recomputeWayGrowth = RecomputeWayGrowthUseCase(
            passageRepository = passages,
            wayGrowthRepository = growths,
        ),
        wayGrowthRepository = growths,
        recentGrowthRepository = recentGrowth,
    )

    @Test
    fun 初回の散歩では通った道が全部育った扱いになる() = runTest {
        passages.passCounts = mapOf(1L to 1, 2L to 1)

        val result = useCase(sessionId = 1L)

        assertEquals(setOf(1L, 2L), result.stageRaisedWayIds)
        assertEquals(listOf(setOf(1L, 2L)), recentGrowth.recorded)
        assertEquals(setOf(1L, 2L), recentGrowth.stageRaisedWayIds.value)
    }

    @Test
    fun 段階が上がった道だけが強調に乗る() = runTest {
        // 1本目は草のまま（1→2回）、2本目は花に上がる（2→3回）
        passages.passCounts = mapOf(1L to 1, 2L to 2)
        useCase(sessionId = 1L)

        passages.passCounts = mapOf(1L to 2, 2L to 3)
        val result = useCase(sessionId = 2L)

        assertEquals(GrowthStage.GRASS, result.growths.single { it.wayId == 1L }.stage)
        assertEquals(GrowthStage.FLOWER, result.growths.single { it.wayId == 2L }.stage)
        assertEquals(setOf(2L), result.stageRaisedWayIds)
    }

    @Test
    fun 何も育たなかった散歩は前回の強調を消す() = runTest {
        passages.passCounts = mapOf(1L to 1)
        useCase(sessionId = 1L)
        assertEquals(setOf(1L), recentGrowth.stageRaisedWayIds.value)

        // 通過が増えなかった（マスタ外を歩いた等）散歩
        val result = useCase(sessionId = 2L)

        assertEquals(emptySet(), result.stageRaisedWayIds)
        assertEquals(emptySet(), recentGrowth.stageRaisedWayIds.value)
    }

    @Test
    fun 通過の作り直しが成長の作り直しより先に走る() = runTest {
        passages.passCounts = mapOf(1L to 1)

        useCase(sessionId = 7L)

        // 対象セッションの passage を作り直したうえで、成長は全件を1回だけ入れ替える
        assertEquals(listOf(7L), passages.replacedSessions)
        assertEquals(1, growths.replaceCount)
    }
}

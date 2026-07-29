package com.walkingrpg.shared.domain.map

import com.walkingrpg.shared.domain.FakeCurrentLocationRepository
import com.walkingrpg.shared.domain.FakeOsmMasterRepository
import com.walkingrpg.shared.domain.FakeRecentGrowthRepository
import com.walkingrpg.shared.domain.FakeWayGrowthRepository
import com.walkingrpg.shared.domain.STRAIGHT_SHAPE
import com.walkingrpg.shared.domain.growth.GrowthStage
import com.walkingrpg.shared.domain.growth.WayGrowth
import com.walkingrpg.shared.domain.testWay
import com.walkingrpg.shared.domain.walk.LocationFix
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 抽象レイヤー（`way_growth` × way マスタ）の組み立て（issue #10）。 */
class GetMapSceneUseCaseTest {

    private fun useCase(
        growths: List<WayGrowth> = emptyList(),
        wayIds: List<Long> = emptyList(),
        fix: LocationFix? = null,
        stageRaised: Set<Long> = emptySet(),
    ) = GetMapSceneUseCase(
        currentLocationRepository = FakeCurrentLocationRepository(fix),
        wayGrowthRepository = FakeWayGrowthRepository(growths),
        osmMasterRepository = FakeOsmMasterRepository(wayIds.map { testWay(it) }),
        recentGrowthRepository = FakeRecentGrowthRepository(stageRaised),
    )

    private fun growth(wayId: Long, stage: GrowthStage, passCount: Int = 1) =
        WayGrowth(wayId = wayId, passCount = passCount, stage = stage)

    private fun fix(latitude: Double, longitude: Double) = LocationFix(
        timestampMs = 0L,
        latitude = latitude,
        longitude = longitude,
        accuracyMeters = 8.0,
    )

    @Test
    fun 成長のある道だけが色づけされる() = runTest {
        // マスタには3本あるが、通ったのは2本
        val scene = useCase(
            growths = listOf(growth(1L, GrowthStage.GRASS), growth(3L, GrowthStage.TREE, 20)),
            wayIds = listOf(1L, 2L, 3L),
        )()

        assertEquals(listOf(1L, 3L), scene.highlights.map { it.wayId }.sorted())
    }

    @Test
    fun 段階と形はそのままUI層へ渡る() = runTest {
        val scene = useCase(
            growths = listOf(growth(1L, GrowthStage.CREATURE, passCount = 50)),
            wayIds = listOf(1L),
        )()

        val highlight = scene.highlights.single()
        assertEquals(GrowthStage.CREATURE, highlight.stage)
        assertEquals(STRAIGHT_SHAPE, highlight.shape)
    }

    @Test
    fun 育っている道ほど後ろに並ぶ() = runTest {
        // 地図SDKは後ろの要素を上に描くので、交差点で濃い色が下に隠れないこと
        val scene = useCase(
            growths = listOf(
                growth(1L, GrowthStage.TREE, passCount = 20),
                growth(2L, GrowthStage.GRASS),
                growth(3L, GrowthStage.FLOWER, passCount = 3),
            ),
            wayIds = listOf(1L, 2L, 3L),
        )()

        assertEquals(
            listOf(GrowthStage.GRASS, GrowthStage.FLOWER, GrowthStage.TREE),
            scene.highlights.map { it.stage },
        )
    }

    @Test
    fun マスタに無いway_idは無視される() = runTest {
        // 対象圏を取り直してOSM側から消えた道。passage は残っているので、
        // マスタを取り直せば戻る＝ここで落とすのは表示だけ
        val scene = useCase(
            growths = listOf(growth(1L, GrowthStage.GRASS), growth(99L, GrowthStage.GRASS)),
            wayIds = listOf(1L),
        )()

        assertEquals(listOf(1L), scene.highlights.map { it.wayId })
    }

    @Test
    fun 直近の散歩で段階が上がった道に印がつく() = runTest {
        val scene = useCase(
            growths = listOf(growth(1L, GrowthStage.GRASS), growth(2L, GrowthStage.FLOWER, 3)),
            wayIds = listOf(1L, 2L),
            stageRaised = setOf(2L),
        )()

        assertEquals(
            mapOf(1L to false, 2L to true),
            scene.highlights.associate { it.wayId to it.isNewlyGrown },
        )
    }

    @Test
    fun 一度も歩いていなければ抽象レイヤーは空になる() = runTest {
        val scene = useCase(wayIds = listOf(1L, 2L))()

        assertEquals(emptyList(), scene.highlights)
    }

    @Test
    fun 現在地が取れなければ広域デフォルトを表示する() = runTest {
        // ユーザー固有の座標は持たないので、寄せ先が無いときは国全体を出す。
        // 色（抽象レイヤー）は現在地とは無関係に出る
        val scene = useCase(
            growths = listOf(growth(1L, GrowthStage.GRASS)),
            wayIds = listOf(1L),
        )()

        assertEquals(MapCamera.WIDE_DEFAULT, scene.camera)
        assertNull(scene.userLocation)
        assertTrue(scene.highlights.isNotEmpty())
    }

    @Test
    fun 現在地が取れたらそこにカメラを寄せる() = runTest {
        val scene = useCase(fix = fix(latitude = 12.0, longitude = 34.0))()

        assertEquals(GeoPoint(12.0, 34.0), scene.camera.center)
        assertTrue(scene.camera.zoom > MapCamera.WIDE_DEFAULT.zoom)
        assertEquals(GeoPoint(12.0, 34.0), scene.userLocation)
    }
}

package com.walkingrpg.shared.domain.map

import com.walkingrpg.shared.domain.walk.CurrentLocationRepository
import com.walkingrpg.shared.domain.walk.LocationFix
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GetMapSceneUseCaseTest {

    private class FakeCurrentLocationRepository(private val fix: LocationFix?) :
        CurrentLocationRepository {
        override suspend fun currentFix(): LocationFix? = fix
    }

    private fun useCase(fix: LocationFix? = null) =
        GetMapSceneUseCase(FakeCurrentLocationRepository(fix))

    private fun fix(latitude: Double, longitude: Double) = LocationFix(
        timestampMs = 0L,
        latitude = latitude,
        longitude = longitude,
        accuracyMeters = 8.0,
    )

    @Test
    fun `現在地が取れなければ広域デフォルトを表示する`() = runTest {
        val scene = useCase()()

        // ユーザー固有の座標は持たないので、寄せ先が無いときは国全体を出す
        assertEquals(MapCamera.WIDE_DEFAULT, scene.camera)
        assertNull(scene.userLocation)
    }

    @Test
    fun `現在地が取れたらそこにカメラを寄せる`() = runTest {
        val scene = useCase(fix(latitude = 12.0, longitude = 34.0))()

        assertEquals(GeoPoint(12.0, 34.0), scene.camera.center)
        assertTrue(scene.camera.zoom > MapCamera.WIDE_DEFAULT.zoom)
        assertEquals(GeoPoint(12.0, 34.0), scene.userLocation)
    }

    @Test
    fun `同じカメラからは必ず同じwayが出る（冪等）`() = runTest {
        assertEquals(useCase()().highlights, useCase()().highlights)
    }

    @Test
    fun `wayはカメラ中心の周りに置かれ、深さが1本ずつ違う`() = runTest {
        val scene = useCase()()
        val highlights = scene.highlights
        val center = MapCamera.WIDE_DEFAULT.center

        assertTrue(highlights.isNotEmpty())
        assertEquals(highlights.map { it.depth }.distinct().size, highlights.size)
        highlights.forEach { highlight ->
            assertTrue(highlight.shape.size >= 2)
            highlight.shape.forEach { point ->
                assertTrue((point.latitude - center.latitude) in -0.01..0.01)
                assertTrue((point.longitude - center.longitude) in -0.01..0.01)
            }
        }
    }

    @Test
    fun `wayは現在地中心のときも現在地の周りに置かれる`() = runTest {
        val scene = useCase(fix(latitude = 12.0, longitude = 34.0))()

        scene.highlights.flatMap { it.shape }.forEach { point ->
            assertTrue((point.latitude - 12.0) in -0.01..0.01)
            assertTrue((point.longitude - 34.0) in -0.01..0.01)
        }
    }
}

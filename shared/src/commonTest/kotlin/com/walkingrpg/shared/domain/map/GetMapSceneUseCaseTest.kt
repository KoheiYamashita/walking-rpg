package com.walkingrpg.shared.domain.map

import com.walkingrpg.shared.domain.walk.CurrentLocationRepository
import com.walkingrpg.shared.domain.walk.LocationFix
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GetMapSceneUseCaseTest {

    private val camera = MapCamera(GeoPoint(latitude = 1.0, longitude = 2.0), zoom = 15.0)

    private class FakeMapCameraRepository(private val camera: MapCamera) : MapCameraRepository {
        override suspend fun initialCamera(): MapCamera = camera
    }

    private class FakeCurrentLocationRepository(private val fix: LocationFix?) :
        CurrentLocationRepository {
        override suspend fun currentFix(): LocationFix? = fix
    }

    private fun useCase(fix: LocationFix? = null) = GetMapSceneUseCase(
        FakeMapCameraRepository(camera),
        FakeCurrentLocationRepository(fix),
    )

    @Test
    fun `現在地が取れなければリポジトリのカメラをそのまま通す`() = runTest {
        val scene = useCase()()

        assertEquals(camera, scene.camera)
        assertNull(scene.userLocation)
    }

    @Test
    fun `現在地が取れたらそこにカメラを合わせる（ズームは据え置き）`() = runTest {
        val fix = LocationFix(
            timestampMs = 0L,
            latitude = 35.5,
            longitude = 139.5,
            accuracyMeters = 8.0,
        )

        val scene = useCase(fix)()

        assertEquals(GeoPoint(35.5, 139.5), scene.camera.center)
        assertEquals(camera.zoom, scene.camera.zoom)
        assertEquals(GeoPoint(35.5, 139.5), scene.userLocation)
    }

    @Test
    fun `同じカメラからは必ず同じwayが出る（冪等）`() = runTest {
        assertEquals(useCase()().highlights, useCase()().highlights)
    }

    @Test
    fun `wayはカメラ中心の周りに置かれ、深さが1本ずつ違う`() = runTest {
        val scene = useCase()()
        val highlights = scene.highlights

        assertTrue(highlights.isNotEmpty())
        assertEquals(highlights.map { it.depth }.distinct().size, highlights.size)
        highlights.forEach { highlight ->
            assertTrue(highlight.shape.size >= 2)
            highlight.shape.forEach { point ->
                assertTrue((point.latitude - camera.center.latitude) in -0.01..0.01)
                assertTrue((point.longitude - camera.center.longitude) in -0.01..0.01)
            }
        }
    }

    @Test
    fun `wayは現在地中心のときも現在地の周りに置かれる`() = runTest {
        val fix = LocationFix(
            timestampMs = 0L,
            latitude = 35.5,
            longitude = 139.5,
            accuracyMeters = 8.0,
        )

        val scene = useCase(fix)()

        scene.highlights.flatMap { it.shape }.forEach { point ->
            assertTrue((point.latitude - 35.5) in -0.01..0.01)
            assertTrue((point.longitude - 139.5) in -0.01..0.01)
        }
    }
}

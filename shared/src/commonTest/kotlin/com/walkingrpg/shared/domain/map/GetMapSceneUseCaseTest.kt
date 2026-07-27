package com.walkingrpg.shared.domain.map

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GetMapSceneUseCaseTest {

    private val camera = MapCamera(GeoPoint(latitude = 1.0, longitude = 2.0), zoom = 15.0)

    private class FakeMapCameraRepository(private val camera: MapCamera) : MapCameraRepository {
        override suspend fun initialCamera(): MapCamera = camera
    }

    private val useCase = GetMapSceneUseCase(FakeMapCameraRepository(camera))

    @Test
    fun `リポジトリのカメラをそのまま通す`() = runTest {
        assertEquals(camera, useCase().camera)
    }

    @Test
    fun `同じカメラからは必ず同じwayが出る（冪等）`() = runTest {
        assertEquals(useCase().highlights, useCase().highlights)
    }

    @Test
    fun `wayはカメラ中心の周りに置かれ、深さが1本ずつ違う`() = runTest {
        val highlights = useCase().highlights

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
}

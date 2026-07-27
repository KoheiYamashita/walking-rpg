package com.walkingrpg.shared.domain.map

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GetMapSceneUseCaseTest {

    private val camera = MapCamera(GeoPoint(latitude = 1.0, longitude = 2.0), zoom = 15.0)

    private class FakeMapAreaRepository(private val area: MapArea) : MapAreaRepository {
        override suspend fun localArea(): MapArea = area
    }

    private fun useCase(tiles: MapTiles) =
        GetMapSceneUseCase(FakeMapAreaRepository(MapArea(camera, tiles)))

    @Test
    fun `リポジトリのカメラとタイルをそのまま通す`() = runTest {
        val tiles = MapTiles.Ready("/tmp/area.pmtiles")

        val scene = useCase(tiles)()

        assertEquals(camera, scene.camera)
        assertEquals(tiles, scene.tiles)
    }

    @Test
    fun `タイル未同梱でも落ちずにシーンを返す`() = runTest {
        val scene = useCase(MapTiles.NotInstalled)()

        assertEquals(MapTiles.NotInstalled, scene.tiles)
        assertTrue(scene.highlights.isNotEmpty())
    }

    @Test
    fun `同じカメラからは必ず同じwayが出る（冪等）`() = runTest {
        val subject = useCase(MapTiles.NotInstalled)

        assertEquals(subject().highlights, subject().highlights)
    }

    @Test
    fun `wayはカメラ中心の周りに置かれ、深さが1本ずつ違う`() = runTest {
        val highlights = useCase(MapTiles.NotInstalled)().highlights

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

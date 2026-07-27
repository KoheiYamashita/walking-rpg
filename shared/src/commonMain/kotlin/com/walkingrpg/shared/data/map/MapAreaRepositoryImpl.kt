package com.walkingrpg.shared.data.map

import com.walkingrpg.shared.domain.map.MapArea
import com.walkingrpg.shared.domain.map.MapAreaRepository
import com.walkingrpg.shared.domain.map.MapTiles
import com.walkingrpg.shared.platform.map.LocalMapSource

/**
 * [MapAreaRepository] の実装。プラットフォーム層の [LocalMapSource] を
 * ドメインモデルへ変換するだけの薄い境界（architecture.md §2）。
 */
internal class MapAreaRepositoryImpl(
    private val localMapSource: LocalMapSource,
) : MapAreaRepository {

    override suspend fun localArea(): MapArea {
        val path = localMapSource.installTiles()
        return MapArea(
            camera = localMapSource.defaultCamera(),
            tiles = if (path == null) MapTiles.NotInstalled else MapTiles.Ready(path),
        )
    }
}

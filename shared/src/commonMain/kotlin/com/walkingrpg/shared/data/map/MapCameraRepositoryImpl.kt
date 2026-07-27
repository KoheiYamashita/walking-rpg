package com.walkingrpg.shared.data.map

import com.walkingrpg.shared.domain.map.MapCamera
import com.walkingrpg.shared.domain.map.MapCameraRepository
import com.walkingrpg.shared.platform.map.MapCameraSource

/**
 * [MapCameraRepository] の実装。プラットフォーム層の [MapCameraSource] を
 * ドメイン向けに包むだけの薄い境界（architecture.md §2）。
 */
internal class MapCameraRepositoryImpl(
    private val mapCameraSource: MapCameraSource,
) : MapCameraRepository {

    override suspend fun initialCamera(): MapCamera = mapCameraSource.defaultCamera()
}

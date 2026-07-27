package com.walkingrpg.shared.data

import com.walkingrpg.shared.domain.walk.LocationPermissionRepository
import com.walkingrpg.shared.domain.walk.LocationPermissionStatus
import com.walkingrpg.shared.platform.LocationPermissionController
import kotlinx.coroutines.flow.StateFlow

/**
 * [LocationPermissionRepository] の実装。プラットフォーム層の
 * [LocationPermissionController] をドメイン層向けに包むだけ。
 */
internal class LocationPermissionRepositoryImpl(
    private val controller: LocationPermissionController,
) : LocationPermissionRepository {

    override val status: StateFlow<LocationPermissionStatus> = controller.status

    override fun refresh() = controller.refresh()

    override fun request() = controller.request()
}

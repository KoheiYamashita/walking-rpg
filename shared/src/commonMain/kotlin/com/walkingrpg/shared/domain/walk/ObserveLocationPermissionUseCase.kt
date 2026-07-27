package com.walkingrpg.shared.domain.walk

import kotlinx.coroutines.flow.Flow

/** 位置情報権限の状態を購読する。 */
class ObserveLocationPermissionUseCase(
    private val repository: LocationPermissionRepository,
) {
    operator fun invoke(): Flow<LocationPermissionStatus> = repository.status
}

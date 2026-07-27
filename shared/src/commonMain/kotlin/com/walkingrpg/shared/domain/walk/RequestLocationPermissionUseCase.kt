package com.walkingrpg.shared.domain.walk

/** 位置情報権限をユーザーに要求する（OSの権限ダイアログ）。 */
class RequestLocationPermissionUseCase(
    private val repository: LocationPermissionRepository,
) {
    operator fun invoke() = repository.request()
}

package com.walkingrpg.shared.domain.walk

/** 位置情報権限の付与状況を読み直す（画面復帰・設定変更後）。 */
class RefreshLocationPermissionUseCase(
    private val repository: LocationPermissionRepository,
) {
    operator fun invoke() = repository.refresh()
}

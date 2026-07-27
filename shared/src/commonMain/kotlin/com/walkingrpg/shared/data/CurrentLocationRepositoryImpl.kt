package com.walkingrpg.shared.data

import com.walkingrpg.shared.domain.walk.CurrentLocationRepository
import com.walkingrpg.shared.domain.walk.LocationFix
import com.walkingrpg.shared.domain.walk.LocationPermissionRepository
import com.walkingrpg.shared.domain.walk.LocationPermissionStatus
import com.walkingrpg.shared.platform.LocationProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [CurrentLocationRepository] の実装。
 *
 * 権限判定は [LocationPermissionRepository]（＝既存の `LocationPermissionController`）に
 * 一本化し、測位は既存の [LocationProvider] を1点だけ流用する。
 * 現在地専用のプラットフォームAPIは増やさない。
 */
internal class CurrentLocationRepositoryImpl(
    private val locationProvider: LocationProvider,
    private val permissionRepository: LocationPermissionRepository,
) : CurrentLocationRepository {

    override suspend fun currentFix(): LocationFix? {
        // 設定画面で権限を変えた直後でも正しく判定できるよう読み直してから見る
        permissionRepository.refresh()
        if (permissionRepository.status.value != LocationPermissionStatus.GRANTED) return null

        return try {
            withTimeoutOrNull(FIX_TIMEOUT_MS) { locationProvider.updates().first() }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            // 測位できない理由（権限剥奪・位置情報OFF・端末側の失敗）は問わない。
            // 現在地はあくまで「寄れたら寄る」ので、失敗しても地図は初期位置で出す。
            null
        }
    }

    private companion object {
        /** 初回fixは数秒かかることがある。待ちすぎて地図が出ないほうが困るので上限を切る。 */
        const val FIX_TIMEOUT_MS = 5_000L
    }
}

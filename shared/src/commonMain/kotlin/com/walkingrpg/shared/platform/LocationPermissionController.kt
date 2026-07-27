package com.walkingrpg.shared.platform

import com.walkingrpg.shared.domain.walk.LocationPermissionStatus
import kotlinx.coroutines.flow.StateFlow

/**
 * 位置情報権限のプラットフォーム境界。
 *
 * OSのパーミッションAPIはこの実装に閉じ込め、上位層は
 * `LocationPermissionRepository`（ドメイン層）越しにしか触らない。
 */
interface LocationPermissionController {

    val status: StateFlow<LocationPermissionStatus>

    /** 現在の付与状況を読み直す。 */
    fun refresh()

    /** OSの権限ダイアログを出す。 */
    fun request()
}

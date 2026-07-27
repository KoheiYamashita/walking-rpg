package com.walkingrpg.shared.domain.walk

import kotlinx.coroutines.flow.StateFlow

/** 位置情報権限の状態。 */
enum class LocationPermissionStatus {
    /** まだ問い合わせていない／判定できない。 */
    UNKNOWN,

    /** 測位してよい。 */
    GRANTED,

    /** 拒否されている（UIで案内を出す）。 */
    DENIED,
}

/**
 * 位置情報権限の境界（architecture.md §2）。
 *
 * 実際のパーミッションAPIはプラットフォーム層に閉じ、
 * ドメイン層・UI層からはこのインターフェースしか見えない。
 */
interface LocationPermissionRepository {

    val status: StateFlow<LocationPermissionStatus>

    /** 現在の付与状況を読み直す（画面復帰時など）。 */
    fun refresh()

    /** OSの権限ダイアログを出す。結果は [status] に反映される。 */
    fun request()
}

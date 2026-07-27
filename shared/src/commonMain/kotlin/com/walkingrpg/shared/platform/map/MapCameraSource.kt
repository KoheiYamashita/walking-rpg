package com.walkingrpg.shared.platform.map

import com.walkingrpg.shared.domain.map.MapCamera

/**
 * 地図の初期表示位置の取得元（プラットフォーム層）。
 *
 * 実装は expect/actual ではなくインターフェース＋DIで注入する（architecture.md §2）。
 * 実在の座標をリポジトリに含めないため、値はGit管理外の `local.properties` から読む。
 */
interface MapCameraSource {
    /** 初期カメラ位置。ローカル設定が無ければ世界全体を表示する。 */
    fun defaultCamera(): MapCamera
}

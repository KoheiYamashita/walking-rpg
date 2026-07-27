package com.walkingrpg.shared.platform.map

import com.walkingrpg.shared.domain.map.MapCamera

/**
 * 端末上のローカル地図素材へのアクセス（プラットフォーム層）。
 *
 * 実装は expect/actual ではなくインターフェース＋DIで注入する（architecture.md §2）。
 * Android実装は assets 同梱のPMTilesを内部ストレージへ展開し、
 * iOS実装は当面プレースホルダ。
 */
interface LocalMapSource {
    /**
     * ローカルPMTilesを利用可能な状態にし、その絶対パスを返す。
     * 同梱されていない場合は null。
     */
    suspend fun installTiles(): String?

    /**
     * 初期カメラ位置。実在の座標をリポジトリに含めないため、
     * ローカル設定（Git管理外の `local.properties`）から読む。未設定なら世界全体。
     */
    fun defaultCamera(): MapCamera
}

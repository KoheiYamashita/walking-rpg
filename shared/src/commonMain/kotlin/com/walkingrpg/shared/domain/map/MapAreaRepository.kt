package com.walkingrpg.shared.domain.map

/**
 * ローカル地図素材の境界（architecture.md §2「Repository」）。
 *
 * ドメイン層はこのインターフェースだけを持つ。
 * assets からの展開・ファイルパス・ローカル設定の読み出しは
 * すべてこの向こう側（data / platform 層）に閉じる。
 */
interface MapAreaRepository {
    /** 端末上に用意されたローカル地図（タイル＋初期カメラ）を返す。 */
    suspend fun localArea(): MapArea
}

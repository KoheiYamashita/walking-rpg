package com.walkingrpg.shared.domain.map

/**
 * 地図の初期表示位置の境界（architecture.md §2「Repository」）。
 *
 * ドメイン層はこのインターフェースだけを持つ。設定の読み出し先
 * （現状はGit管理外のローカル設定、将来は直近セッションの位置）は
 * この向こう側（data / platform 層）に閉じる。
 */
interface MapCameraRepository {
    suspend fun initialCamera(): MapCamera
}

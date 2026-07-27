package com.walkingrpg.shared.domain.map

/**
 * 地図まわりのドメインモデル（architecture.md §2 ドメイン層・純Kotlin）。
 *
 * 描画APIにも地図SDKにも依存しない。MapLibreの型への変換はUI層（composeApp）で行う。
 */

/** 緯度経度。座標そのものはリポジトリ外（ローカル設定・タイルファイル）から来る。 */
data class GeoPoint(
    val latitude: Double,
    val longitude: Double,
)

/** 地図の初期表示位置。 */
data class MapCamera(
    val center: GeoPoint,
    val zoom: Double,
)

/** 端末上のローカルタイル（PMTiles）の状態。 */
sealed interface MapTiles {
    /** 端末のファイルシステム上に展開済み。[absolutePath] はPMTilesファイルの絶対パス。 */
    data class Ready(val absolutePath: String) : MapTiles

    /** 未同梱・未展開。地図は表示できない（スパイクではその旨を画面に出す）。 */
    data object NotInstalled : MapTiles
}

/** ローカル地図の素材一式。プラットフォーム層が用意し、データ層が包む。 */
data class MapArea(
    val camera: MapCamera,
    val tiles: MapTiles,
)

/**
 * way（道路セグメント）1本ぶんの色づけ。
 *
 * design.md §8「実地図＋抽象レイヤー」の抽象レイヤー側。
 * 具体的な色はUI層が [depth] から決める（ドメインは色を知らない）。
 */
data class WayHighlight(
    val wayId: String,
    val shape: List<GeoPoint>,
    /** 育ちの段階。0 = 未踏。大きいほど濃く塗る。 */
    val depth: Int,
)

/** 地図画面が描画に必要とするものの全体。 */
data class MapScene(
    val camera: MapCamera,
    val tiles: MapTiles,
    val highlights: List<WayHighlight>,
)

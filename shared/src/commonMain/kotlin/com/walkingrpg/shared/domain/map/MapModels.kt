package com.walkingrpg.shared.domain.map

/**
 * 地図まわりのドメインモデル（architecture.md §2 ドメイン層・純Kotlin）。
 *
 * 描画APIにも地図SDKにも依存しない。MapLibreの型への変換はUI層（composeApp）で行う。
 */

/** 緯度経度。座標そのものはリポジトリ外（ローカル設定）から来る。 */
data class GeoPoint(
    val latitude: Double,
    val longitude: Double,
)

/** 地図の初期表示位置。 */
data class MapCamera(
    val center: GeoPoint,
    val zoom: Double,
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

/**
 * 地図画面が描画に必要とするものの全体。
 *
 * 背景タイルはOpenFreeMapからオンライン取得する（architecture.md §1）ので、
 * ドメインが持つのはカメラと抽象レイヤーだけ。
 */
data class MapScene(
    val camera: MapCamera,
    val highlights: List<WayHighlight>,
)

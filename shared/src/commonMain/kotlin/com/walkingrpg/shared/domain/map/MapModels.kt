package com.walkingrpg.shared.domain.map

/**
 * 地図まわりのドメインモデル（architecture.md §2 ドメイン層・純Kotlin）。
 *
 * 描画APIにも地図SDKにも依存しない。MapLibreの型への変換はUI層（composeApp）で行う。
 */

/** 緯度経度。 */
data class GeoPoint(
    val latitude: Double,
    val longitude: Double,
)

/** 地図の表示位置。 */
data class MapCamera(
    val center: GeoPoint,
    val zoom: Double,
) {
    companion object {
        /**
         * 現在地が取れないときの初期表示。国全体が入る広域ズームで、
         * 「どこも指していない」ことが見て分かる状態にする。
         *
         * ユーザー固有の座標（自宅・対象圏）はリポジトリに置かない方針なので、
         * 設定ファイルから読む仕組みは持たない。地図が寄るのは
         * 位置情報の権限を許可して現在地が取れたときだけ。
         */
        val WIDE_DEFAULT: MapCamera = MapCamera(
            center = GeoPoint(latitude = 36.2, longitude = 138.3),
            zoom = 4.5,
        )
    }
}

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
 * ドメインが持つのはカメラと抽象レイヤー、それに現在地だけ。
 *
 * @param userLocation 画面を開いた時点の現在地。権限がない・測位できないときは `null`
 *  （このとき [camera] はローカル設定由来の初期位置になる）。追従はしない（issue #10 の領分）。
 */
data class MapScene(
    val camera: MapCamera,
    val highlights: List<WayHighlight>,
    val userLocation: GeoPoint? = null,
)

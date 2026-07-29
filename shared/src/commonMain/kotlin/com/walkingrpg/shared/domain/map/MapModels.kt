package com.walkingrpg.shared.domain.map

import com.walkingrpg.shared.domain.growth.GrowthStage

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
 * 具体的な色はUI層が [stage] から決める（ドメインは色を知らない）。
 *
 * 段階を `Int` の「濃さ」に潰さず [GrowthStage] のまま渡すのは、
 * 潰した瞬間に「5段階しかない」ことがドメイン側で失われ、
 * UI層の `when` が網羅チェックを受けられなくなるから
 * （`depth = 7` のような、成長側にありえない値を表現できてしまう）。
 * 色を決めるのがUI層である、という責務分担はそのまま。
 *
 * @param isNewlyGrown 直近の散歩で段階が上がった道。design.md §8「わずかな揺らぎ程度」の
 *  強調に使う（どう見せるかはUI層が決める）。
 */
data class WayHighlight(
    val wayId: Long,
    val shape: List<GeoPoint>,
    val stage: GrowthStage,
    val isNewlyGrown: Boolean = false,
)

/**
 * 地図画面が描画に必要とするものの全体。
 *
 * 背景タイルはOpenFreeMapからオンライン取得する（architecture.md §1）ので、
 * ドメインが持つのはカメラと抽象レイヤー、それに現在地だけ。
 *
 * @param userLocation 画面を開いた時点の現在地。権限がない・測位できないときは `null`
 *  （このとき [camera] はローカル設定由来の初期位置になる）。
 *  記録中の**追従**はこのモデルには入れない：追従するかどうかは「いま記録中か」だけで決まり、
 *  地図を組み立て直さなくても切り替わる状態なので、UI層（`MapUiState`）が
 *  `ObserveIsWalkingUseCase` から直接持つ。
 */
data class MapScene(
    val camera: MapCamera,
    val highlights: List<WayHighlight>,
    val userLocation: GeoPoint? = null,
)

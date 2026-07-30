package com.walkingrpg.app.ui.map

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.walkingrpg.shared.domain.growth.GrowthStage
import com.walkingrpg.shared.domain.map.GeoPoint
import com.walkingrpg.shared.domain.map.MapCamera
import com.walkingrpg.shared.domain.map.WayHighlight

/**
 * 地図ビューの薄いラッパ（architecture.md §1「CMPで共通化できない唯一の部品」）。
 *
 * ネイティブの地図SDKをここだけに閉じ込め、common側は
 * ドメインモデル（[MapCamera] / [WayHighlight]）しか渡さない。
 * 背景タイルはOpenFreeMapからオンラインで取得する。
 *
 * @param highlights way単位の色づけ（design.md §8「実地図＋抽象レイヤー」）。
 * @param userLocation 現在地。`null`（権限なし・測位できない）のときはマーカーを出さない。
 *  非nullは「上位で権限GRANTEDを確認済み」であることを表すので、地図SDK側の
 *  現在地表示もこのフラグだけで出し分ける（権限判定を二重に持たない）。
 * @param followUserLocation 現在地にカメラを追従させる（記録中のみ true）。
 *  `userLocation` が `null` のときは現在地マーカー自体が出ないので効かない。
 */
@Composable
expect fun MapCanvas(
    camera: MapCamera,
    highlights: List<WayHighlight>,
    userLocation: GeoPoint?,
    followUserLocation: Boolean,
    modifier: Modifier = Modifier,
)

/**
 * 育ちの段階（[WayHighlight.stage]）に対応する色。
 *
 * 「色の深まり」で育ちを見せる（design.md §8）。色の決定はUI層の責務なので
 * ドメインモデルには持たせない。
 *
 * 5色は**同系色の濃度違い**で、色相を振らない：色相を振ると「緑の道」「青の道」の
 * ように種類の違いに見えてしまい、段階＝1本の道が育っていく順序であることが伝わらない。
 * 背景（OpenFreeMap `positron`：低彩度グレー）の上で、薄い若草から深い常緑へ沈んでいく。
 * 具象イラスト（草・花の絵）は描かない＝図鑑の中だけ、という決定どおり。
 */
internal fun stageColorHex(stage: GrowthStage): String = when (stage) {
    GrowthStage.GRASS -> "#B7DDA8"
    GrowthStage.FLOWER -> "#8CC79B"
    GrowthStage.SHRUB -> "#5CA588"
    GrowthStage.TREE -> "#2F7D6B"
    GrowthStage.CREATURE -> "#1B5245"
}

/**
 * [stageColorHex] をComposeの [Color] にしたもの。
 *
 * 月次スナップショット（issue #17）は地図SDKではなく自前のCanvasで描くので、
 * 16進文字列ではなく [Color] が要る。色の**決定**は [stageColorHex] 側1箇所のままにして、
 * ここは変換だけを引き受ける＝アルバムの色と地図の色がずれない。
 */
internal fun stageColor(stage: GrowthStage): Color =
    Color(OPAQUE_ALPHA or stageColorHex(stage).removePrefix("#").toInt(radix = 16))

/** `#RRGGBB` にはアルファが無いので、不透明として補う。 */
private const val OPAQUE_ALPHA = 0xFF000000.toInt()

/** 抽象レイヤーの線の太さ（dp相当）。 */
internal const val WAY_LINE_WIDTH = 6f

/**
 * 直近の散歩で段階が上がった道の線の太さ。
 *
 * design.md §8 は「わずかな揺らぎ程度」と言っているが、**アニメーションは入れない**：
 * MapLibreのスタイルには時間で変化するプロパティが無く、揺らすには毎フレーム
 * `setGeoJson`／プロパティ更新を回す自前のループが要る。地図は毎日開く画面なので
 * 常時再描画は電池に効くうえ、「地図描画にアート量を投入しない」という同じ節の
 * 方針にも反する。太さを +2 する静的な強調で「今日はここが育った」は十分読める。
 */
internal const val NEWLY_GROWN_LINE_WIDTH = 8f

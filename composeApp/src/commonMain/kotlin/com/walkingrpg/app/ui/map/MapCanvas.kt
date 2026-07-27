package com.walkingrpg.app.ui.map

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.walkingrpg.shared.domain.map.MapCamera
import com.walkingrpg.shared.domain.map.WayHighlight

/**
 * 地図ビューの薄いラッパ（architecture.md §1「CMPで共通化できない唯一の部品」）。
 *
 * ネイティブの地図SDKをここだけに閉じ込め、common側は
 * ドメインモデル（[MapCamera] / [WayHighlight]）しか渡さない。
 *
 * @param tilesPath ローカルPMTilesの絶対パス。呼び出し側が存在を保証する。
 * @param highlights way単位の色づけ（design.md §8「実地図＋抽象レイヤー」）。
 */
@Composable
expect fun MapCanvas(
    camera: MapCamera,
    tilesPath: String,
    highlights: List<WayHighlight>,
    modifier: Modifier = Modifier,
)

/**
 * 育ちの段階（[WayHighlight.depth]）に対応する色。
 *
 * 「色の深まり」で育ちを見せる（design.md §8）。色の決定はUI層の責務なので
 * ドメインモデルには持たせない。スパイクなので3段階だけ。
 */
internal fun depthColorHex(depth: Int): String = when (depth) {
    0 -> "#00000000"
    1 -> "#9BC4B2"
    2 -> "#4E8D7C"
    else -> "#20574B"
}

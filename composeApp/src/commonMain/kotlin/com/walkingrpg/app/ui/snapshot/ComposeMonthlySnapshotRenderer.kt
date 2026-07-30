package com.walkingrpg.app.ui.snapshot

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.walkingrpg.app.ui.map.stageColor
import com.walkingrpg.shared.domain.snapshot.MonthlySnapshotRenderer
import com.walkingrpg.shared.domain.snapshot.MonthlySnapshotStats
import com.walkingrpg.shared.domain.snapshot.SnapshotBounds
import com.walkingrpg.shared.domain.snapshot.SnapshotProjection
import com.walkingrpg.shared.domain.snapshot.SnapshotScene
import com.walkingrpg.shared.domain.snapshot.SnapshotWay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [MonthlySnapshotRenderer] のCompose実装。**コンポジションの外で描く。**
 *
 * ## MapLibreのsnapshot APIを使わない
 * issue #17 の「環境によって面倒な場合は自前Canvas描画に切り替える」を採った。理由は3つ：
 * - **片OSでしか動かない**。iOSの `MapCanvas` はまだプレースホルダで、MapLibreの
 *   オフスクリーンレンダリングを通す経路が無い。永久保存する絵が端末によって
 *   別物になる（あるいは片方で作られない）のは耐久コアとして成立しない
 * - **背景タイルはオンライン取得**（OpenFreeMap）。借り物の絵を焼き込んだ1枚は
 *   「永久」を名乗れない（圏外で作った月と自宅で作った月で中身が変わる）
 * - **描きたいのは抽象レイヤーそのもの**（design.md §8）。データは全部手元にあるので、
 *   地図エンジンに依存する理由がない
 *
 * ## 凝らない
 * design.md §8「地図描画にアート量を投入しない」に従い、出すのは
 * 単色の背景＋色の付いた道＋月ラベルだけ。アニメーション・揺らぎ・具象イラストは無い
 * （揺らぎを入れない理由は `MapCanvas.NEWLY_GROWN_LINE_WIDTH` のKDocと同じ）。
 * 色は地図画面と同じ [stageColor]（`stageColorHex` 由来）＝アルバムを開いたときの色が
 * 地図で見ていた色と一致する。
 *
 * ## コンポジション不要の描画
 * `ImageBitmap` に `Canvas` を張り、[CanvasDrawScope] で `DrawScope` の作法のまま描く。
 * `@Composable` にすると「画面に出ていないと絵が作れない」ことになり、
 * 起動時の生成（`AppViewModel`）から呼べない。
 */
internal class ComposeMonthlySnapshotRenderer : MonthlySnapshotRenderer {

    override suspend fun render(
        scene: SnapshotScene,
        stats: MonthlySnapshotStats,
    ): ByteArray = withContext(Dispatchers.Default) {
        val bitmap = ImageBitmap(IMAGE_SIZE_PX, IMAGE_SIZE_PX)
        val size = Size(IMAGE_SIZE_PX.toFloat(), IMAGE_SIZE_PX.toFloat())

        CanvasDrawScope().draw(
            density = Density(density = 1f),
            layoutDirection = LayoutDirection.Ltr,
            canvas = Canvas(bitmap),
            size = size,
        ) {
            drawRect(color = BACKGROUND_COLOR, size = size)
            val bounds = scene.bounds
            if (bounds != null) drawWays(scene.ways, bounds)
            drawMonthLabel(scene.month.iso)
        }

        bitmap.encodeToPng()
    }

    /**
     * 道を段階の低い順に描く（順序は `GrownWayShapes` が保証済み）。
     *
     * 線は折れ線をセグメントごとの [DrawScope.drawLine] で引く。`Path` を組まないのは、
     * 端点を丸める（[StrokeCap.Round]）だけで繋ぎ目が滑らかに見えるうえ、
     * 地図側（MapLibreの `lineCap: round` / `lineJoin: round`）と見た目が揃うから。
     */
    private fun DrawScope.drawWays(ways: List<SnapshotWay>, bounds: SnapshotBounds) {
        val strokeWidth = IMAGE_SIZE_PX * WAY_LINE_WIDTH_RATIO
        for (way in ways) {
            val color = stageColor(way.stage)
            val points = way.shape.map { point ->
                val normalized = SnapshotProjection.project(point, bounds)
                Offset(
                    x = (normalized.x * IMAGE_SIZE_PX).toFloat(),
                    y = (normalized.y * IMAGE_SIZE_PX).toFloat(),
                )
            }
            for (index in 1 until points.size) {
                drawLine(
                    color = color,
                    start = points[index - 1],
                    end = points[index],
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round,
                    alpha = WAY_LINE_ALPHA,
                )
            }
        }
    }

    /**
     * 月ラベル（'2026-06'）を左上に描く。
     *
     * **文字は自前の線分で引く。** `TextMeasurer` はフォントリゾルバを要求し、
     * それを共通コードで手に入れる道筋がコンポジションの外には無い
     * （`rememberTextMeasurer` は `@Composable`）。プラットフォームごとの文字描画に
     * 割るほどの情報量ではない――出すのは数字4桁＋区切り＋2桁だけなので、
     * 7セグメント（[SevenSegmentDigits]）で引く。「凝らない」の範囲に収まるし、
     * どのOSでも同じ形の1枚になる（フォントの有無で絵が変わらない）。
     */
    private fun DrawScope.drawMonthLabel(iso: String) {
        SevenSegmentDigits.draw(
            drawScope = this,
            text = iso,
            origin = Offset(
                x = IMAGE_SIZE_PX * LABEL_MARGIN_RATIO,
                y = IMAGE_SIZE_PX * LABEL_MARGIN_RATIO,
            ),
            digitHeight = IMAGE_SIZE_PX * LABEL_HEIGHT_RATIO,
            strokeWidth = IMAGE_SIZE_PX * LABEL_STROKE_RATIO,
            color = LABEL_COLOR,
        )
    }

    private companion object {
        /**
         * 出力解像度（正方形）。
         *
         * 正方形なのは [SnapshotProjection] がアスペクト比を守って正方形に収める前提だから。
         * 1080pxは「アルバムで拡大しても粗が見えず、月1枚なら容量も気にならない」程度
         * （単色の線だけのPNGなので実測で数十KB）。
         */
        const val IMAGE_SIZE_PX = 1080

        /**
         * 背景色。OpenFreeMap `positron`（低彩度グレー基調）の紙色に寄せる。
         *
         * 地図画面の背景がタイルで、こちらは単色という違いは残るが、
         * **道の色が同じ明度の紙の上に乗る**ので「見ていた地図の色」として通じる
         * （`stageColorHex` のKDoc「背景の上で薄い若草から深い常緑へ沈んでいく」）。
         */
        val BACKGROUND_COLOR = Color(0xFFF2F1EE)

        /** 線の太さ（画像の辺に対する比）。1080pxで約4.3px。 */
        const val WAY_LINE_WIDTH_RATIO = 0.004f

        /** 地図画面のレイヤー（`lineOpacity(0.85f)`）と揃える。 */
        const val WAY_LINE_ALPHA = 0.85f

        /** 月ラベルの色。道より沈ませる（絵の主役は道）。 */
        val LABEL_COLOR = Color(0xFF8A8A85)

        const val LABEL_MARGIN_RATIO = 0.045f
        const val LABEL_HEIGHT_RATIO = 0.05f
        const val LABEL_STROKE_RATIO = 0.006f
    }
}

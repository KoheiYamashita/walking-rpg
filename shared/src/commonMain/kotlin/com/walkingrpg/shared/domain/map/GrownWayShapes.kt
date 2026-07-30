package com.walkingrpg.shared.domain.map

import com.walkingrpg.shared.domain.growth.GrowthStage
import com.walkingrpg.shared.domain.growth.WayGrowth
import com.walkingrpg.shared.domain.osm.Way

/**
 * 育ちのある道（`way_growth`）を、形（`way.geometry`）と突き合わせる純関数。
 *
 * design.md §8 の抽象レイヤーを描く側は2つある（地図画面＝[GetMapSceneUseCase]、
 * 月次スナップショット＝`GetMonthlySnapshotSceneUseCase`）。
 * **突き合わせの規則をどちらにも書くと必ず食い違う**ので、ここ1箇所に置く：
 * どの道を落とすか（マスタに無い・点が足りない）と、どの順で返すか（段階の低い順）は
 * 「歩いた街の見え方」そのものであって、画面ごとの都合ではない。
 *
 * 成長の行があるのは1回でも通った道だけ（[WayGrowth]）なので、
 * 「歩いた道だけが色を持つ」がそのまま出る＝未踏の道を0段階で並べるコードは要らない。
 */
object GrownWayShapes {

    /** 線として描ける最小の頂点数。1点しかないwayはマスタが壊れているので捨てる。 */
    const val MIN_SHAPE_POINTS: Int = 2

    /**
     * 育ちのある道を「形＋段階」にして返す。
     *
     * マスタに無いway ID（対象圏を取り直してOSM側から消えた道など）は黙って落とす。
     * 形が引けないものは描きようがなく、`passage` 側は真実として残っているので、
     * 次にマスタを取り直せば戻る＝ここで落とすのは表示だけ。
     *
     * 段階の低い順に並べるのは、地図SDKも自前の描画も**後の要素を上に描く**から。
     * 交差点で重なったとき、育っている道の色が下に隠れないようにする。
     */
    fun of(growths: List<WayGrowth>, ways: List<Way>): List<GrownWayShape> {
        if (growths.isEmpty()) return emptyList()

        val shapeByWayId = ways.associate { it.id to it.geometry }
        return growths
            .mapNotNull { growth ->
                val shape = shapeByWayId[growth.wayId] ?: return@mapNotNull null
                if (shape.size < MIN_SHAPE_POINTS) return@mapNotNull null
                GrownWayShape(wayId = growth.wayId, shape = shape, stage = growth.stage)
            }
            .sortedBy { it.stage }
    }
}

/**
 * 育ちのある道1本の「形＋段階」。
 *
 * 表示側のモデル（[WayHighlight] / `SnapshotWay`）に変換される途中の形で、
 * 画面ごとの都合（直近の散歩で育ったかどうか、など）はまだ乗っていない。
 */
data class GrownWayShape(
    val wayId: Long,
    val shape: List<GeoPoint>,
    val stage: GrowthStage,
)

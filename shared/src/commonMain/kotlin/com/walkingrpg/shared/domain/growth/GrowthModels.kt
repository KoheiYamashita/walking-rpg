package com.walkingrpg.shared.domain.growth

/**
 * 道の成長のドメインモデル（design.md §4.1〜4.2、architecture.md §4「導出」）。
 *
 * 純Kotlin。SQLDelightの行型もプラットフォームAPIもここには現れない。
 */

/**
 * 道単体の成長段階（design.md §4.2「草 → 花 → 低木 → 木 → 生き物が住みつく」）。
 *
 * **段階は有限**。無限にするのは分岐（朝の性質・水の性質…）との組み合わせであって、
 * 段階の数ではない＝「Lv.847の草」を作らないための決定（design.md §11「上限：なし（分岐で無限）。
 * ただし線形インフレは禁止」）。分岐は MVP 後回しなので、ここには現れない。
 *
 * 減衰がないので、段階は**上がるだけ**。ただし「上げた記録」を持つのではなく、
 * 通過回数から毎回引き直す（[WayGrowthCalculator]）。同じ passage 列からは必ず
 * 同じ段階が出るので、導出テーブルはいつ捨てて作り直してもよい。
 *
 * enum の宣言順＝段階の低い順。比較には `ordinal` ではなく [compareTo]（enum標準）を使う。
 */
enum class GrowthStage {
    /** 草。1回でも通れば必ずここまで来る＝「歩いたのに何も起きない」を作らない。 */
    GRASS,

    /** 花。 */
    FLOWER,

    /** 低木。 */
    SHRUB,

    /** 木。 */
    TREE,

    /** 生き物が住みつく。道単体の到達点（この先は分岐で伸ばす）。 */
    CREATURE,
}

/**
 * 1本の道の成長状態（architecture.md §4 `way_growth(way_id, pass_count, stage, branch_attr)`）。
 *
 * `branch_attr`（分岐成長）は MVP 後回しの決定事項なので、このモデルには入れない。
 *
 * これは**導出キャッシュ**であって真実の源ではない。真実は `passage`（さらに遡れば
 * `location_sample`）だけが持っていて、この値はいつ捨てても [RecomputeWayGrowthUseCase] で
 * 同じものが再生する。
 *
 * @param passCount その道を通った回数。1回の通過ごとに1増える（初回踏破ではない＝design.md §4.1）。
 *  0回の道は「行が無い」で表す：未踏の道までキャッシュに並べても、way マスタの写しが増えるだけ。
 * @param stage [passCount] から引いた段階。保存するのは、毎回全件計算し直さずに地図へ流すため
 *  （値の正しさの根拠は常に [passCount] 側にある）。
 */
data class WayGrowth(
    val wayId: Long,
    val passCount: Int,
    val stage: GrowthStage,
) {
    init {
        require(passCount >= 1) { "passCount は1以上（0回の道は行を作らない）" }
    }
}

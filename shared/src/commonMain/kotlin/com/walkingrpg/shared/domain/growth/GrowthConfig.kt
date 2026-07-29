package com.walkingrpg.shared.domain.growth

/**
 * 道の成長の閾値。**調整する数字は全部ここに集める**（issue #9）。
 *
 * `way_growth` は `passage` から何度でも作り直せる導出キャッシュ（WayGrowth.sq）なので、
 * ここを変えて [RecomputeWayGrowthUseCase] を流せば全部の道の段階が引き直される。
 * マイグレーションも「上げ直し」も要らない＝減衰なし設計の実装上の配当
 * （architecture.md §4「導出テーブルはすべて passage から再計算できること」）。
 *
 * ## 既定値の設計基準
 *
 * 基準単位は **1日30分 ≒ 2km の散歩1回**（design.md §11「標準歩行距離」）。
 * MVP対象圏は歩行対象の道が約220本・23km（design.md §9）なので、
 * 1回の散歩で通る道はおよそ20〜40本。自宅至近の道は往復で1回の散歩に2回のることもある。
 *
 * 閾値は**段階が上がるほど広く**取る（1 → 3 → 8 → 20 → 50、おおよそ2.5倍ずつ）。
 * 等間隔にすると「次の段階まであとN回」が永久に一定＝線形インフレになり、
 * design.md §4.2「Lv.847の草を作らない」に反する。倍々にしていくと、
 * 序盤は歩くたびに変化が見え、後半は「この道は特別」という密度に変わる。
 *
 * 毎日1回そこを通る道での到達日数（往復で2回のる道なら半分）：
 *
 * | 段階 | 通過回数 | 毎日通ったときの目安 |
 * |---|---|---|
 * | 草 | 1回 | 初回の散歩 |
 * | 花 | 3回 | 3日（＝MVPの1週目に必ず見える） |
 * | 低木 | 8回 | 1週間強 |
 * | 木 | 20回 | 3週間 |
 * | 生き物 | 50回 | 2ヶ月弱 |
 *
 * 「木」を3週間に置いたのは、単純踏破が2週間弱で終わる（design.md §9）ぶん、
 * **踏破が終わったあとにも段階が上がり続ける**ようにするため。MVPの検証仮説は
 * 「踏破後も歩き続けるか」（design.md §10）なので、ここが1週間で頭打ちになると
 * 検証そのものが成立しない。逆に「生き物」を2ヶ月弱に置いたのは、
 * 図鑑の「同じ川に10回通って初めて現れる」（design.md §4.4）より遠くに置いて、
 * 道の到達点が図鑑より先に来ないようにするため。
 *
 * 実プレイで合わなければ**ここだけ**触って再計算する（テストは
 * `WayGrowthCalculatorTest` が閾値そのものではなく「段階が広がっていくこと」を守る）。
 */
data class GrowthConfig(
    /** 花になる通過回数。3回：週に数回の散歩でも1週目のうちに1段は上がる。 */
    val flowerPassCount: Int = 3,

    /** 低木になる通過回数。8回：花からさらに5回＝「よく通る道」がここで分かれ始める。 */
    val shrubPassCount: Int = 8,

    /** 木になる通過回数。20回：単純踏破が終わる2週間弱より先に置く（上記の表）。 */
    val treePassCount: Int = 20,

    /** 生き物が住みつく通過回数。50回：毎日通う道でも2ヶ月弱かかる、道単体の到達点。 */
    val creaturePassCount: Int = 50,
) {
    init {
        // 段階は必ず上がる方向にしか進まないので、閾値も狭義単調増加でなければならない。
        // （同値を許すと、あいだの段階が絶対に出ない＝表示されない段階が生まれる）
        require(flowerPassCount > GRASS_PASS_COUNT) { "flowerPassCount は $GRASS_PASS_COUNT より大きい値" }
        require(shrubPassCount > flowerPassCount) { "shrubPassCount は flowerPassCount より大きい値" }
        require(treePassCount > shrubPassCount) { "treePassCount は shrubPassCount より大きい値" }
        require(creaturePassCount > treePassCount) { "creaturePassCount は treePassCount より大きい値" }
    }

    /**
     * [stage] に到達するのに必要な通過回数。
     *
     * 段階の判定（[WayGrowthCalculator.stageOf]）と「次の段階まであと何回か」の表示
     * （#10 の帰宅後サマリ）で同じ数字を使うための入口。
     */
    fun minPassCountFor(stage: GrowthStage): Int = when (stage) {
        GrowthStage.GRASS -> GRASS_PASS_COUNT
        GrowthStage.FLOWER -> flowerPassCount
        GrowthStage.SHRUB -> shrubPassCount
        GrowthStage.TREE -> treePassCount
        GrowthStage.CREATURE -> creaturePassCount
    }

    companion object {
        /**
         * 草に必要な通過回数は1で固定（調整対象にしない）。
         *
         * `passage` が1件あるということは「その道を歩いた」ことそのものなので、
         * ここを2以上にすると「歩いたのに何も起きなかった散歩」が生まれる。
         * design.md §2 の負のフレーム排除に照らして、それは作らない。
         */
        const val GRASS_PASS_COUNT: Int = 1

        /** 既定の閾値。UIから触らせる予定はないので、差し替えはDI（`sharedModule`）で行う。 */
        val DEFAULT: GrowthConfig = GrowthConfig()
    }
}

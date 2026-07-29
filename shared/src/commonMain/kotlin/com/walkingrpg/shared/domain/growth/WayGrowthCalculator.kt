package com.walkingrpg.shared.domain.growth

import com.walkingrpg.shared.domain.matching.Passage

/**
 * 通過（`passage`）→ 道の成長（`way_growth`）の変換（design.md §4.1「状態 = 歩行ログの累積」）。
 *
 * **純関数**で、現在時刻も乱数も使わない：同じ通過列・同じ閾値なら必ず同じ結果になる。
 * 「前の段階」を入力に取らないのがこの設計の要で、上げ幅を積算しないので
 * 二重に足す・足し忘れるという事故が起きえない。導出テーブルを消して
 * [RecomputeWayGrowthUseCase] を流し直せば、いつでも同じ状態が再生する
 * （architecture.md §4「導出テーブルはすべて passage から再計算できること」）。
 *
 * 減衰なし（design.md §4.1）は、ここに時間を入力しないことで担保される。
 * 「最後に通ってから何日」を見た瞬間に、この関数は冪等でなくなる。
 */
object WayGrowthCalculator {

    /**
     * 通過回数から段階を引く。
     *
     * 閾値を跨いだ**いちばん上の段階**を採る（回数がどれだけ増えても上限は生き物）。
     *
     * @param passCount 1以上。0回の道には成長が無い（[WayGrowth] 参照）。
     */
    fun stageOf(passCount: Int, config: GrowthConfig = GrowthConfig.DEFAULT): GrowthStage {
        require(passCount >= 1) { "passCount は1以上（0回の道は行を作らない）" }
        return GrowthStage.entries.last { passCount >= config.minPassCountFor(it) }
    }

    /**
     * 道ごとの通過回数から成長を作る。結果はway ID順（保存順で結果が変わらないように）。
     *
     * 回数が0以下の道は落とす。SQL側の `GROUP BY` からは0件のwayは出てこないが、
     * 呼び出し側でMapを組み立てたときに0が混ざりうるので、ここで吸収しておく。
     */
    fun growths(
        passCountsByWay: Map<Long, Int>,
        config: GrowthConfig = GrowthConfig.DEFAULT,
    ): List<WayGrowth> = passCountsByWay.entries
        .filter { (_, passCount) -> passCount >= 1 }
        .sortedBy { (wayId, _) -> wayId }
        .map { (wayId, passCount) ->
            WayGrowth(wayId = wayId, passCount = passCount, stage = stageOf(passCount, config))
        }

    /**
     * 通過列そのものから成長を作る（テストと、件数が小さいうちの素直な経路）。
     *
     * 集計してから [growths] に渡すだけ＝SQLの `GROUP BY` 経由と同じ関数を通るので、
     * 「アプリはSQLで集計、テストはKotlinで集計」でも結果は必ず一致する。
     */
    fun growthsFrom(
        passages: List<Passage>,
        config: GrowthConfig = GrowthConfig.DEFAULT,
    ): List<WayGrowth> = growths(
        passCountsByWay = passages.groupingBy { it.wayId }.eachCount(),
        config = config,
    )
}

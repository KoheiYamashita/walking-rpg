package com.walkingrpg.shared.domain.codex

/**
 * 図鑑の閾値。**調整する数字は全部ここに集める**（issue #13。`GrowthConfig` と同じ流儀）。
 *
 * `codex_progress` は `passage` から何度でも作り直せる導出キャッシュ（CodexProgress.sq）なので、
 * ここを変えて [RecomputeCodexProgressUseCase] を流せば全種の進捗が引き直される。
 * マイグレーションも「上げ直し」も要らない＝減衰なし設計の実装上の配当
 * （architecture.md §4「導出テーブルはすべて passage から再計算できること」）。
 *
 * 種ごとの出現回数（3〜10回）は [SpeciesCatalog] 側にある：あちらは
 * 「どの生き物が何回で出るか」という**世界の骨**で、ここは
 * 「どのくらい近ければ訪問と数えるか」という判定の物差し。混ぜない。
 */
data class CodexConfig(
    /**
     * POIの近傍と見なす距離（メートル）。この距離以内を通る道を歩いたら
     * 「そのPOIを訪問した」と数える（[CodexProgressCalculator]）。
     *
     * 40m：POIは点（`poi.lat/lon`）でしか持っていない（Poi.sq）ので、公園や河川のような
     * 広がりのある地物も代表点1つに畳まれている。その代表点から数十m離れた道が
     * 「その公園の前の道」であることは普通なので、道幅・敷地の広がりぶんの余裕が要る。
     *
     * **map matching の [com.walkingrpg.shared.domain.matching.MapMatchingConfig.maxSnapDistanceMeters]
     * （20m）は流用しない。** あちらは「GPSの誤差を吸収してどの道にいたかを決める」ための
     * 物差しで、精度が上限（15m）まで荒れても隣の道に乗り移らない値として選ばれている。
     * ここで測るのは誤差ではなく**地物と道の位置関係**なので、同じ数字である理由がない。
     * 広げすぎると鉄道と農地が同じ道で同時に訪問扱いになって棚の区別が消えるため、
     * 「道1本ぶん向こうまでは届かない」程度に留める。
     */
    val poiProximityMeters: Double = 40.0,

    /**
     * 出現の何回手前から予兆を出すか。
     *
     * 2回：design.md §4.4「残り回数は数字で見せず、予兆で匂わせる」。0回手前（＝出る回に
     * 初めて出す）では予兆が予兆にならず、遠すぎると「ずっと出ている置物」になって
     * 期待を作らない。いちばん短い種が3回（[SpeciesCatalog]）なので、
     * 2回手前なら「1回通ったら次の散歩から匂い始める」＝**必ず1回は予兆を経由して**出る。
     *
     * 訪問回数そのものが閾値-2に届いていない種には何も出さないので、
     * 図鑑を開いても「？」が並ぶだけ（＝残り回数は漏れない）。
     */
    val foreshadowLeadVisits: Int = 2,
) {
    init {
        require(poiProximityMeters > 0.0) { "poiProximityMeters は正の値" }
        require(foreshadowLeadVisits >= 1) { "foreshadowLeadVisits は1以上" }
    }

    companion object {
        /** 既定の閾値。UIから触らせる予定はないので、差し替えはDI（`sharedModule`）で行う。 */
        val DEFAULT: CodexConfig = CodexConfig()
    }
}

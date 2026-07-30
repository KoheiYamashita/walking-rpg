package com.walkingrpg.shared.domain.llm

/**
 * LLM生成の調整値。**触る数字は全部ここに集める**（issue #14。`WeatherFetchConfig` と同じ流儀）。
 *
 * @param maxGenerationsPerRun ドレイン1回で投げるリクエストの上限。
 * @param maxFailuresPerRun 1回のドレインで許す失敗の回数（下記「打ち切りの二段構え」）。
 * @param poiFlavorMaxTokens 地点フレーバー1件の出力上限トークン数。
 * @param speciesDescriptionMaxTokens 図鑑の記述文1件の出力上限トークン数。
 * @param requireUnmeteredNetwork 従量課金でない回線（Wi-Fi等）でしか事前バッチを走らせないか。
 */
data class LlmGenerationConfig(
    val maxGenerationsPerRun: Int = DEFAULT_MAX_GENERATIONS_PER_RUN,
    val maxFailuresPerRun: Int = DEFAULT_MAX_FAILURES_PER_RUN,
    val poiFlavorMaxTokens: Int = DEFAULT_POI_FLAVOR_MAX_TOKENS,
    val speciesDescriptionMaxTokens: Int = DEFAULT_SPECIES_DESCRIPTION_MAX_TOKENS,
    val requireUnmeteredNetwork: Boolean = true,
) {
    init {
        require(maxGenerationsPerRun > 0) { "maxGenerationsPerRun は正の値" }
        require(maxFailuresPerRun > 0) { "maxFailuresPerRun は正の値" }
        require(poiFlavorMaxTokens > 0) { "poiFlavorMaxTokens は正の値" }
        require(speciesDescriptionMaxTokens > 0) { "speciesDescriptionMaxTokens は正の値" }
    }

    companion object {
        /**
         * 1回のドレインで投げる上限＝40件。
         *
         * 対象圏500m〜1kmのPOIは数百件（design.md §7）。初回に全部投げると、
         * 帰宅直後の1回で数百リクエスト＝数十秒の通信になり、失敗したときの
         * 取り直しも重い。ドレインは冪等（キャッシュに無いものだけ作る）なので、
         * 起動と散歩終了のたびに40件ずつ埋めれば数回で追いつく。
         *
         * 「路上でLLMを待たせたら負け」（design.md §7）の裏返しとして、
         * **家の中でなら少しずつ何度でも走ってよい**。
         */
        const val DEFAULT_MAX_GENERATIONS_PER_RUN: Int = 40

        /**
         * 打ち切りまでの失敗回数＝3。
         *
         * ## 打ち切りの二段構え
         * 1. **通信・設定の失敗（401・タイムアウト等）はその場で run ごと打ち切る**：
         *    キーが無効・圏外なら次の地点でも同じ結果で、有料APIを無駄に叩くだけ
         * 2. **応答の形が変（JSONが崩れている等）は数回だけ続ける**：モデルが
         *    たまたま余計な前置きを付けた、という単発の事故で残り全件を諦めるのは早い。
         *    ただし毎回崩れるモデル・設定もあるので、3回で見切る
         */
        const val DEFAULT_MAX_FAILURES_PER_RUN: Int = 3

        /**
         * 地点フレーバーの出力上限＝300トークン。
         *
         * design.md §7「1地点あたり約300トークン、Haiku級で十分」に合わせた。
         * 出すのは1〜2文なので実際はもっと短く終わるが、JSONの体裁ぶんの余裕を持たせてある
         * （足りないと途中で切れて [PoiFlavorResponseParser] が読めなくなる）。
         */
        const val DEFAULT_POI_FLAVOR_MAX_TOKENS: Int = 300

        /**
         * 図鑑の記述文の出力上限＝400トークン。
         *
         * 地点フレーバー（1〜2文）より長い2〜3文を出させるので、そのぶん上げてある。
         * 種は手書きのカタログで有限（十数件）なので、1件あたりを少し贅沢にしても
         * 総額は地点フレーバー（数百件）に比べて無視できる。
         */
        const val DEFAULT_SPECIES_DESCRIPTION_MAX_TOKENS: Int = 400

        /** 既定値。差し替えはDI（`sharedModule`）で行う。UIからは触らせない。 */
        val DEFAULT: LlmGenerationConfig = LlmGenerationConfig()
    }
}

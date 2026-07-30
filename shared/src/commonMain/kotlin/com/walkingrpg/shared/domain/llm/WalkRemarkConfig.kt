package com.walkingrpg.shared.domain.llm

/**
 * パートナーの一言の材料の調整値（issue #16。`LlmGenerationConfig` と同じ流儀で
 * **触る数字は全部ここに集める**）。
 *
 * [LlmGenerationConfig] と分けているのは性格が違うから：あちらは「いつ・何件投げるか」
 * （課金と通信の蓋）だが、ここは「何を材料にするか」（design.md §5 の記憶の深さ・
 * 押し忘れの言及範囲）で、数字を動かすとプロンプトが変わる＝**指紋が変わる**。
 * 動かしたら既存の一言は作り直される（それが正しい：材料が変われば別の一言になる）。
 *
 * @param memoryFirstVisitMinDaysAgo 「この道を初めて歩いたのは…」として語ってよい古さの下限。
 *  半年前なら「初めて歩いた日」の話になるが、2週間前だと単に前回の話なので深さが出ない。
 * @param memorySeasonMinDaysAgo 「季節前にも歩いた」の下限（design.md §5 の「季節前」）。
 *  90日＝ひと季節。
 * @param memoryMonthMinDaysAgo 「1ヶ月前にも歩いた」の範囲の下限。
 * @param memoryMonthMaxDaysAgo 同じ範囲の上限。25〜45日を「ひと月ほど前」として扱う
 *  （厳密に30日だけを見ると、ほとんどの散歩で該当せず記憶が出なくなる）。
 * @param stepsMentionMaxDaysAgo 押し忘れに言及してよい古さの上限（日）。
 *  「昨日はけっこう歩いたんだね」（design.md §3）が成立するのは直近だけで、
 *  1週間前の歩数を持ち出すのは記録の指摘に近づく。
 * @param stepsMentionMinSteps 言及する下限の歩数。買い物ぶんの数百歩に「歩いたね」と
 *  言うのは、事実の水増し（design.md §2 の自己透明性）になる。
 * @param stepsGoodThreshold 「けっこう歩いた」に上がる歩数。
 * @param stepsManyThreshold 「たくさん歩いた」に上がる歩数。
 * @param utteranceHistoryLimit 切り口の除外集合を作るときに見る発話履歴の件数
 *  （その場所ぶん・新しい順）。全部見ないのは、切り口が全部「使用済み」に埋まったあと
 *  いちばん古いものを再利用する（`RemarkAngleSelector`）ぶんの窓として十分だから。
 */
data class WalkRemarkConfig(
    val memoryFirstVisitMinDaysAgo: Int = DEFAULT_MEMORY_FIRST_VISIT_MIN_DAYS_AGO,
    val memorySeasonMinDaysAgo: Int = DEFAULT_MEMORY_SEASON_MIN_DAYS_AGO,
    val memoryMonthMinDaysAgo: Int = DEFAULT_MEMORY_MONTH_MIN_DAYS_AGO,
    val memoryMonthMaxDaysAgo: Int = DEFAULT_MEMORY_MONTH_MAX_DAYS_AGO,
    val stepsMentionMaxDaysAgo: Int = DEFAULT_STEPS_MENTION_MAX_DAYS_AGO,
    val stepsMentionMinSteps: Int = DEFAULT_STEPS_MENTION_MIN_STEPS,
    val stepsGoodThreshold: Int = DEFAULT_STEPS_GOOD_THRESHOLD,
    val stepsManyThreshold: Int = DEFAULT_STEPS_MANY_THRESHOLD,
    val utteranceHistoryLimit: Int = DEFAULT_UTTERANCE_HISTORY_LIMIT,
) {
    init {
        require(memoryFirstVisitMinDaysAgo > memorySeasonMinDaysAgo) {
            "「初めて歩いた日」は「季節前」より古い（深さの順序が壊れる）"
        }
        require(memorySeasonMinDaysAgo > memoryMonthMaxDaysAgo) {
            "「季節前」は「ひと月ほど前」より古い（深さの順序が壊れる）"
        }
        require(memoryMonthMinDaysAgo in 1..memoryMonthMaxDaysAgo) {
            "「ひと月ほど前」の範囲は 1 日以上で下限 ≦ 上限"
        }
        require(stepsMentionMaxDaysAgo >= 1) { "押し忘れの言及は前日以前（1日以上前）" }
        require(stepsMentionMinSteps > 0) { "stepsMentionMinSteps は正の値" }
        require(stepsMentionMinSteps <= stepsGoodThreshold) { "下限 ≦ 「けっこう」の閾値" }
        require(stepsGoodThreshold < stepsManyThreshold) { "「けっこう」＜「たくさん」" }
        require(utteranceHistoryLimit > 0) { "utteranceHistoryLimit は正の値" }
    }

    companion object {
        /** 「初めて歩いた日」として語る下限＝180日前。半年前なら「初めて」の話になる。 */
        const val DEFAULT_MEMORY_FIRST_VISIT_MIN_DAYS_AGO: Int = 180

        /** 「季節前」の下限＝90日前（ひと季節）。 */
        const val DEFAULT_MEMORY_SEASON_MIN_DAYS_AGO: Int = 90

        /** 「ひと月ほど前」の下限＝25日前。 */
        const val DEFAULT_MEMORY_MONTH_MIN_DAYS_AGO: Int = 25

        /** 「ひと月ほど前」の上限＝45日前。 */
        const val DEFAULT_MEMORY_MONTH_MAX_DAYS_AGO: Int = 45

        /**
         * 押し忘れに言及する上限＝2日前。
         *
         * 歩数の取り込み（`ImportDailyStepsUseCase`）が見るのも昨日と今日の2日だけなので、
         * それより広く見ても材料は無い。
         */
        const val DEFAULT_STEPS_MENTION_MAX_DAYS_AGO: Int = 2

        /** 言及する下限＝2000歩（これ未満は「歩いた日」として扱わない）。 */
        const val DEFAULT_STEPS_MENTION_MIN_STEPS: Int = 2_000

        /** 「けっこう歩いた」＝5000歩から。 */
        const val DEFAULT_STEPS_GOOD_THRESHOLD: Int = 5_000

        /** 「たくさん歩いた」＝1万歩から。 */
        const val DEFAULT_STEPS_MANY_THRESHOLD: Int = 10_000

        /**
         * 発話履歴を見る件数＝12件。
         *
         * 切り口（[RemarkAngle]）は8種なので、同じ場所ぶん12件あれば
         * 「全部使い切ったか」「いちばん古く使ったのはどれか」を決めるのに足りる。
         */
        const val DEFAULT_UTTERANCE_HISTORY_LIMIT: Int = 12

        /** 既定値。差し替えはDI（`sharedModule`）で行う。UIからは触らせない。 */
        val DEFAULT: WalkRemarkConfig = WalkRemarkConfig()
    }
}

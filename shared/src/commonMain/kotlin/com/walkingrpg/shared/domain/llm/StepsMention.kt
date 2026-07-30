package com.walkingrpg.shared.domain.llm

/**
 * 「開始を押し忘れた日」への言及（design.md §3・issue #7 の出口）。
 *
 * > **開始を押し忘れた日**：歩数計APIから距離だけ拾い、パートナーが言及する
 * > （「今日はけっこう歩いたんだね」）。位置がないので街は育たないが、
 * > **なかったことにもならない**
 *
 * `step_import` に行があって、その日に `walk_session` が1件もない日だけが対象
 * （セッションがある日は押し忘れではないので言及しない）。
 *
 * ## 数値は渡さない
 * 歩数そのものはプロンプトに載せない（[StepsAmount] の3段階に丸める）。理由は2つ：
 * - 一言に数字を並べさせないため（[WalkReviewRemarkPromptBuilder.systemPrompt]）。
 *   材料として数字を渡すと、そのまま書き写した文が返ってくる
 * - プロンプト指紋を安定させるため。歩数計の値は同期が遅れて後から増える
 *   （`StepImportRepository.upsert`）ので、生の値を渡すと同じ散歩でも指紋が動く
 *
 * @param amount 歩数の粗い量。
 * @param daysAgo その散歩の日から見て何日前か（1以上＝当日は対象外）。
 */
data class StepsMention(
    val amount: StepsAmount,
    val daysAgo: Int,
) {
    init {
        require(daysAgo >= 1) { "押し忘れの言及はその散歩の日より前だけ: $daysAgo" }
    }

    companion object {
        fun of(steps: Int, daysAgo: Int, config: WalkRemarkConfig): StepsMention = StepsMention(
            amount = StepsAmount.of(steps, config),
            daysAgo = daysAgo,
        )
    }
}

/** 歩数の粗い量（「すこし / けっこう / たくさん」）。閾値は [WalkRemarkConfig]。 */
enum class StepsAmount {
    SOME,
    GOOD,
    MANY,
    ;

    companion object {
        fun of(steps: Int, config: WalkRemarkConfig): StepsAmount = when {
            steps >= config.stepsManyThreshold -> MANY
            steps >= config.stepsGoodThreshold -> GOOD
            else -> SOME
        }
    }
}

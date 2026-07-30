package com.walkingrpg.shared.domain.llm

import com.walkingrpg.shared.domain.growth.GrowthStage
import com.walkingrpg.shared.domain.review.TimeOfDay
import com.walkingrpg.shared.domain.review.WalkReview
import com.walkingrpg.shared.domain.weather.WeatherCondition
import kotlin.math.round

/**
 * 振り返りのパートナーの一言（帰宅後に語る2〜3文）のプロンプトと後始末。
 *
 * `PoiFlavor.kt` / `SpeciesDescription.kt` と同じ3点セット（プロンプトビルダー／
 * 応答パーサ／定型文）。違うのは材料の出どころで、こちらは
 * **その散歩1回ぶんの確定データ**（`WalkReview`）を渡す。
 *
 * design.md §5「事実は歩行ログのDBから渡し、LLMは文章化のみ担当。
 * → ハルシネーションが構造的に起きない」の実装がここ。
 *
 * 純Kotlin。JSONの読み取りは [LlmJsonText]（ドメイン層に
 * kotlinx.serialization を持ち込まないため）。
 */

/**
 * プロンプトに載せる事実。**ここに無いものはLLMに渡らない。**
 *
 * ## 位置情報は渡さない
 * 座標・自宅・住所・地点名はどれも渡さない（`PoiFlavorFacts` の「座標は渡さない」より
 * さらに狭い）。振り返りの一言は「今日の散歩がどんなだったか」であって場所の説明ではないので、
 * 位置を外に出す理由がない。道の名前も渡さない：無名の生活道路が大半で、
 * 名前がある道だけ特別扱いされると「どこを歩いたか」が外に出る方向に働く。
 * 記憶（[memory]）が入っても同じで、載るのは「この道」「どれくらい前か」だけ
 * ＝`utterance_log.place_ref`（`way:<id>` 等の内部キー）も渡さない。
 *
 * ## ブランク（歩かなかった期間）は渡さない
 * 「前回の散歩から何日空いたか」に相当する事実は**この型に存在しない**。
 * 禁止文（[WalkReviewRemarkPromptBuilder.systemPrompt]）だけでなくデータの形でも守るのは、
 * 材料があると「久しぶりだね」の方向に滲むから（design.md §2「負のフレームを作らない」）。
 *
 * @param distanceKmText 距離を「2.3」のような文字列に丸めたもの。数値のまま渡さないのは、
 *  同じ散歩で毎回同じ指紋（[PromptHash]）にするため（浮動小数の表記揺れを避ける）。
 * @param grownWayCount 段階が上がった道の本数。
 * @param stageChanges 段階の変化（「低木から木へ」）。本数だけでは「1段階育った」の
 *  中身が渡らないので、変化した段階の呼び名を並べる。
 * @param discoveredSpeciesNames その散歩で新しく出た種の名前。**発見は嬉しさの頂点**なので
 *  名前まで渡す（図鑑に載った＝もう伏せる必要がない）。
 * @param foreshadowTexts 予兆が立った種の予兆文（`Species.foreshadowText`）。
 *  **種名は渡さない**：まだ未発見なので、名前を渡した瞬間に予兆が予告になる（design.md §4.4）。
 * @param weather 天候（未取得なら `null`＝触れさせない）。
 * @param angle 今日の切り口（design.md §5「同じ切り口の再使用を禁止」）。
 *  1つだけ渡して「ここを中心に書く」と指示する（禁止リストは渡さない。
 *  理由は [RemarkAngle] のKDoc）。
 * @param memory この道の記憶（無ければ `null`）。
 * @param stepsMention 歩数だけ残っている日への言及（無ければ `null`）。
 */
data class WalkReviewRemarkFacts(
    val timeOfDay: TimeOfDay,
    val distanceKmText: String,
    val durationMinutes: Int,
    val grownWayCount: Int,
    val stageChanges: List<String> = emptyList(),
    val discoveredSpeciesNames: List<String> = emptyList(),
    val foreshadowTexts: List<String> = emptyList(),
    val weather: WeatherCondition? = null,
    // 既定値は置かない：切り口は「毎回決めるもの」で、省略できると
    // 生成側と読み側で別の値が入る（＝指紋が食い違う）道が生まれる。
    val angle: RemarkAngle,
    val memory: WalkMemory? = null,
    val stepsMention: StepsMention? = null,
) {
    companion object {
        /**
         * 振り返りと、その外から集めた材料から、渡してよい事実だけを抜き出す。
         *
         * **変換の口はここ1つだけ**にしてある：生成側と読み側が別の道で事実を組むと、
         * プロンプト指紋が食い違って生成済みの一言が使われなくなる
         * （[GetWalkRemarkContextUseCase] のKDoc）。
         *
         * @param context 記憶・押し忘れ・切り口・時間帯（[GetWalkRemarkContextUseCase] が調達）。
         */
        fun of(review: WalkReview, context: WalkRemarkContext): WalkReviewRemarkFacts =
            WalkReviewRemarkFacts(
                timeOfDay = context.timeOfDay,
                distanceKmText = review.distanceKm.toOneDecimal(),
                durationMinutes = (review.durationMs / 60_000L).toInt(),
                grownWayCount = review.grownWays.size,
                stageChanges = review.grownWays
                    .map { grown -> "${grown.from.stageLabel()}から${grown.to.stageLabel()}へ" }
                    .distinct(),
                discoveredSpeciesNames = review.discoveredSpecies.map { it.name },
                foreshadowTexts = review.approachedSpecies.map { it.foreshadowText },
                // 天候不明で確定した行は「取れていない」と同じ扱いにする（触れさせない）。
                weather = review.weather?.takeIf { it.isKnown }?.condition,
                angle = context.angle,
                memory = context.memory,
                stepsMention = context.stepsMention,
            )
    }
}

/** 振り返りの一言のプロンプトを組み立てる（純関数）。 */
object WalkReviewRemarkPromptBuilder {

    /**
     * 役割と禁止事項。**design.md §5「責めない。ただ喜ぶ」と §7「事実は書かせない」の実装**。
     *
     * 数字の羅列を禁じているのは、数値サマリが画面に既に出ているから
     * （同じことを文章でも言うと1分で閉じられる密度が壊れる）。距離や本数を渡すのは
     * 「どんな散歩だったか」を掴ませるためで、書き写させるためではない。
     *
     * 出力をJSON固定にしているのは、モデルが前置きを付けても
     * [WalkReviewRemarkResponseParser] が本文だけを取り出せるようにするため。
     */
    val systemPrompt: String = """
        あなたは散歩の相棒です。帰ってきた相手に、今日の散歩について短く声をかけます。

        守ること:
        - 日本語で2〜3文、合わせて100文字程度まで。
        - 静かなトーンで、褒めすぎない。感嘆符は使わない。
        - 責めない・急かさない・催促しない・次を指示しない。「もっと歩こう」とは言わない。
        - 渡された事実だけを踏まえる。歩いた場所・地名・施設・歴史・季節の断定はしない。
        - 実在の場所の事実は書かない。調べれば真偽が決まることは一切書かない。
        - 数字を並べない。距離や本数をそのまま書き写さず、様子として触れる。
        - 予兆の手がかりは、それが何の生き物かを当てて書かない（まだ分かっていない）。
        - 歩かなかった日・散歩の間隔・空いた期間には一切触れない。
        - 「久しぶり」「しばらく」「ようやく」のような、間隔をほのめかす言い方をしない。
        - 「今日はこの切り口を中心に書く」で指定された話題から書く。他は無理に詰め込まない。
        - 出力はJSONのみ。{"remark": "本文"} の形だけを返し、前後に説明やコードブロックを付けない。
    """.trimIndent()

    /** 事実だけを並べた依頼文。並び順を固定するのは、同じ散歩で毎回同じ指紋にするため。 */
    fun userPrompt(facts: WalkReviewRemarkFacts): String = buildString {
        append("時間帯: ").append(facts.timeOfDay.label())
        append('\n')
        append("歩いた距離: ").append(facts.distanceKmText).append("km")
        append('\n')
        append("歩いた時間: ").append(facts.durationMinutes).append("分")
        append('\n')
        append("育った道の数: ").append(facts.grownWayCount).append("本")
        if (facts.stageChanges.isNotEmpty()) {
            append('\n')
            append("道の育ち方: ").append(facts.stageChanges.joinToString("、"))
        }
        if (facts.discoveredSpeciesNames.isNotEmpty()) {
            append('\n')
            append("今日はじめて出会ったもの: ").append(facts.discoveredSpeciesNames.joinToString("、"))
        }
        if (facts.foreshadowTexts.isNotEmpty()) {
            append('\n')
            append("見かけた手がかり（何のものかは分かっていない）: ")
            append(facts.foreshadowTexts.joinToString(" / "))
        }
        facts.weather?.let { condition ->
            append('\n')
            append("天候: ").append(condition.label())
        }
        // 記憶は「この道」「どれくらい前か」だけ。道の名前も内部キーも出さない。
        facts.memory?.let { memory ->
            append('\n')
            append("この道の記憶: ").append(memory.label())
        }
        // 歩数は粗い量だけ（数値は渡さない。StepsMention のKDoc）。
        facts.stepsMention?.let { mention ->
            append('\n')
            append("記録は残っていないが歩いた日: ").append(mention.label())
        }
        // 切り口は最後に置く（事実を読んだあとの指示として効かせる）。
        append('\n')
        append("今日はこの切り口を中心に書く: ").append(facts.angle.label())
    }

    /**
     * 1件ぶんの生成依頼。
     *
     * @param maxTokens [LlmGenerationConfig.walkReviewRemarkMaxTokens] を渡す。
     */
    fun request(facts: WalkReviewRemarkFacts, maxTokens: Int): LlmRequest = LlmRequest(
        systemPrompt = systemPrompt,
        userPrompt = userPrompt(facts),
        maxTokens = maxTokens,
    )

    /**
     * その事実で作られた一言かどうかを判定する指紋（`llm_cache.prompt_hash`）。
     *
     * 生成側（[GenerateWalkReviewRemarkUseCase]）と読み出し側
     * （[ObserveWalkReviewRemarkUseCase]）は [request] 経由で同じ値を計算する。
     * 公開しているのは、指紋の作り方を知らないと
     * 「生成済みの行」を用意できない外側（UI層のテスト・#20のデバッグ表示）のため。
     */
    fun promptHash(facts: WalkReviewRemarkFacts, maxTokens: Int): String =
        request(facts, maxTokens).promptHash()
}

/**
 * `{"remark": "..."}` から本文を取り出す（純関数）。
 *
 * エスケープの解き方は [LlmJsonText] に置いてある。ここに残っているのはキー名だけ。
 *
 * @return 取り出せた本文（前後の空白は落とす）。見つからない・空・途中で切れているなら `null`
 *  ＝呼び出し側は定型文（[WalkReviewRemarkFallback]）で凌ぐ。
 */
object WalkReviewRemarkResponseParser {

    private const val KEY = "remark"

    fun parse(raw: String): String? = LlmJsonText.value(raw, KEY)
}

/**
 * 生成できていない散歩で使う定型文（design.md §7「失敗・圏外なら定型文で成立する」）。
 *
 * **振り返りでいちばん出る定型文**：一言は帰宅直後に1件だけ生成するので、圏外で帰った日は
 * 必ずここが出る（地点・種と違って事前に作っておけない）。それでも画面が完成して見えるよう、
 * 何が起きた散歩かで文を変える。事実は含めない（含めた瞬間にLLMに禁じているルールを
 * 自分で破ることになる）ので、数字も場所も入れない。
 */
object WalkReviewRemarkFallback {

    fun text(facts: WalkReviewRemarkFacts): String = when {
        facts.discoveredSpeciesNames.isNotEmpty() ->
            "今日は、いままで見なかったものに出会えたね。図鑑が一つ埋まった。"

        facts.foreshadowTexts.isNotEmpty() ->
            "何かの気配があったね。もう少し通えば、正体が分かるかもしれない。"

        facts.grownWayCount > 0 -> "いつもの道が、また少し育ったよ。おかえり。"

        else -> "おかえり。今日も歩いたね。"
    }
}

/**
 * 振り返りに出す一言。
 *
 * @param isGenerated 生成済みの文章か（`false` なら定型文）。画面では区別しないが、
 *  遅延差し込みの前後を分けるのに使う（`ReviewViewModel`）。
 */
data class WalkReviewRemark(
    val text: String,
    val isGenerated: Boolean,
)

/** プロンプトに書く時間帯の呼び名。 */
private fun TimeOfDay.label(): String = when (this) {
    TimeOfDay.MORNING -> "朝"
    TimeOfDay.DAYTIME -> "日中"
    TimeOfDay.EVENING -> "夕方"
    TimeOfDay.NIGHT -> "夜"
}

/** プロンプトに書く天候の呼び名。UNKNOWN はここに来ない（渡す前に落としてある）。 */
private fun WeatherCondition.label(): String = when (this) {
    WeatherCondition.CLEAR -> "晴れ"
    WeatherCondition.CLOUDY -> "曇り"
    WeatherCondition.RAIN -> "雨"
    WeatherCondition.SNOW -> "雪"
    WeatherCondition.FOG -> "霧"
    WeatherCondition.THUNDER -> "雷雨"
    WeatherCondition.UNKNOWN -> "不明"
}

/**
 * 段階の呼び名（design.md §4.2「草 → 花 → 低木 → 木 → 生き物が住みつく」）。
 * `null` は未踏＝行が無かった状態（`WayStageRaise.from`）。
 */
private fun GrowthStage?.stageLabel(): String = when (this) {
    null -> "何もない状態"
    GrowthStage.GRASS -> "草"
    GrowthStage.FLOWER -> "花"
    GrowthStage.SHRUB -> "低木"
    GrowthStage.TREE -> "木"
    GrowthStage.CREATURE -> "生き物が住む道"
}

/**
 * プロンプトに書く切り口の呼び名（「今日はこの切り口を中心に書く」の値）。
 * 内部の enum 名（`utterance_log.angle` に入る値）はそのまま出さない。
 */
private fun RemarkAngle.label(): String = when (this) {
    RemarkAngle.DISCOVERY -> "今日はじめて出会ったもの"
    RemarkAngle.FORESHADOW -> "見かけた手がかり"
    RemarkAngle.MEMORY -> "この道の記憶"
    RemarkAngle.GROWTH -> "道の育ち"
    RemarkAngle.STEPS -> "記録は残っていないが歩いた日"
    RemarkAngle.WEATHER -> "天候"
    RemarkAngle.TIME_OF_DAY -> "その時間帯の様子"
    RemarkAngle.DISTANCE -> "歩いた道のりの様子"
}

/**
 * プロンプトに書く記憶の言い方。
 *
 * 日数は[粗い言い方][roughDaysAgoText]に丸める：一言に数字を書かせないためと、
 * 「43日前」のような精度が思い出の語り方として不自然なため。
 */
private fun WalkMemory.label(): String = when (depth) {
    WalkMemoryDepth.FIRST_VISIT -> "この道を初めて歩いたのは${roughDaysAgoText(daysAgo)}"
    WalkMemoryDepth.SEASON_AGO -> "この道は${roughDaysAgoText(daysAgo)}にも歩いた"
    WalkMemoryDepth.MONTH_AGO -> "この道は${roughDaysAgoText(daysAgo)}にも歩いた"
    WalkMemoryDepth.RECENT -> "この道は${roughDaysAgoText(daysAgo)}にも歩いた"
}

/** プロンプトに書く歩数の言い方（数値は出さない）。 */
private fun StepsMention.label(): String =
    "${roughDaysAgoText(daysAgo)}、${amount.label()}歩いた"

/** 歩数の粗い量の呼び名。 */
private fun StepsAmount.label(): String = when (this) {
    StepsAmount.SOME -> "すこし"
    StepsAmount.GOOD -> "けっこう"
    StepsAmount.MANY -> "たくさん"
}

/**
 * 「何日前」を粗い言い方に丸める。
 *
 * 段の切り方は [WalkRemarkConfig] の閾値とは独立でよい（あちらは「どの記憶を選ぶか」、
 * こちらは「選んだものをどう言うか」）。数値をそのまま出さないのは、
 * 一言に数字を並べさせないため（[WalkReviewRemarkPromptBuilder.systemPrompt]）。
 */
private fun roughDaysAgoText(daysAgo: Int): String = when {
    daysAgo <= 1 -> "昨日"
    daysAgo <= 3 -> "数日前"
    daysAgo <= 10 -> "一週間ほど前"
    daysAgo <= 20 -> "半月ほど前"
    daysAgo <= 45 -> "ひと月ほど前"
    daysAgo <= 100 -> "二、三ヶ月前"
    daysAgo <= 200 -> "半年ほど前"
    daysAgo <= 400 -> "一年ほど前"
    else -> "何年か前"
}

/** 小数第1位まで（「2.3」）。指紋を安定させるため、桁を必ず1つ出す。 */
private fun Double.toOneDecimal(): String {
    val scaled = round(this * 10).toLong()
    return "${scaled / 10}.${scaled % 10}"
}

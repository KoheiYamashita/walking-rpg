package com.walkingrpg.shared.domain.llm

import com.walkingrpg.shared.domain.codex.CodexCategory
import com.walkingrpg.shared.domain.codex.Species
import com.walkingrpg.shared.domain.growth.GrowthStage
import com.walkingrpg.shared.domain.review.ReviewGrownWay
import com.walkingrpg.shared.domain.review.TimeOfDay
import com.walkingrpg.shared.domain.review.WalkReview
import com.walkingrpg.shared.domain.weather.SessionWeather
import com.walkingrpg.shared.domain.weather.WeatherCondition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 振り返りの一言の3点セット（プロンプト・パーサ・定型文）。
 *
 * 見たいのは3つ：
 * - **プロンプトに位置情報が乗らない**（座標・自宅・地名・道の名前）
 * - 未発見の種の**名前が乗らない**（予兆が予告にならない。design.md §4.4）
 * - 応答が壊れていたら `null`＝定型文で凌ぐ
 */
class WalkReviewRemarkTest {

    private val kingfisher = Species(
        id = "water_kingfisher",
        name = "テストカワセミ",
        category = CodexCategory.WATER,
        requiredVisitCount = 10,
        foreshadowText = "水辺に、青い羽根が一枚落ちている。",
    )

    private fun review(
        distanceMeters: Double = 2_300.0,
        durationMs: Long = 30 * 60_000L,
        weather: SessionWeather? = null,
        grownWays: List<ReviewGrownWay> = emptyList(),
        discovered: List<Species> = emptyList(),
        approached: List<Species> = emptyList(),
    ) = WalkReview(
        sessionId = 1L,
        startedAtMs = 1_767_225_600_000L,
        endedAtMs = 1_767_225_600_000L + durationMs,
        durationMs = durationMs,
        distanceMeters = distanceMeters,
        weather = weather,
        grownWays = grownWays,
        discoveredSpecies = discovered,
        approachedSpecies = approached,
        codexDiscoveredCount = 1,
        codexTotalCount = 17,
    )

    /**
     * 材料（[WalkRemarkContext]）は本番では [GetWalkRemarkContextUseCase] が調達するが、
     * ここで見たいのはプロンプトの作り方だけなので直接置く。
     */
    private fun context(
        timeOfDay: TimeOfDay = TimeOfDay.EVENING,
        angle: RemarkAngle = RemarkAngle.DISTANCE,
        memory: WalkMemory? = null,
        stepsMention: StepsMention? = null,
    ) = WalkRemarkContext(
        timeOfDay = timeOfDay,
        angle = angle,
        placeRef = "day",
        memory = memory,
        stepsMention = stepsMention,
    )

    private fun facts(review: WalkReview, context: WalkRemarkContext = context()) =
        WalkReviewRemarkFacts.of(review, context)

    @Test
    fun 距離は小数第1位の文字列で渡る() {
        assertEquals("2.3", facts(review()).distanceKmText)
        assertEquals("0.0", facts(review(distanceMeters = 20.0)).distanceKmText)
    }

    @Test
    fun 段階の変化が呼び名で渡る() {
        val grown = listOf(
            ReviewGrownWay(wayId = 1L, name = "けやき通り", from = GrowthStage.SHRUB, to = GrowthStage.TREE),
            ReviewGrownWay(wayId = 2L, name = null, from = null, to = GrowthStage.GRASS),
        )

        val prompt = WalkReviewRemarkPromptBuilder.userPrompt(facts(review(grownWays = grown)))

        assertTrue(prompt.contains("低木から木へ"), prompt)
        assertTrue(prompt.contains("何もない状態から草へ"), prompt)
        assertFalse(prompt.contains("けやき通り"), "道の名前は渡さない")
    }

    @Test
    fun プロンプトに位置情報は乗らない() {
        val prompt = WalkReviewRemarkPromptBuilder.userPrompt(
            facts(review(grownWays = listOf(ReviewGrownWay(1L, "けやき通り", null, GrowthStage.GRASS)))),
        )

        listOf("35.", "139.", "けやき", "way", "自宅").forEach { forbidden ->
            assertFalse(prompt.contains(forbidden), "$forbidden がプロンプトに乗っている: $prompt")
        }
    }

    @Test
    fun 予兆は文だけ渡して種名は渡さない() {
        val prompt = WalkReviewRemarkPromptBuilder.userPrompt(
            facts(review(approached = listOf(kingfisher))),
        )

        assertTrue(prompt.contains(kingfisher.foreshadowText), prompt)
        assertFalse(prompt.contains(kingfisher.name), "未発見の種の名前は渡さない（予告になる）")
    }

    @Test
    fun 発見した種は名前ごと渡す() {
        // もう図鑑に載っているので伏せる意味がない
        val prompt = WalkReviewRemarkPromptBuilder.userPrompt(
            facts(review(discovered = listOf(kingfisher))),
        )

        assertTrue(prompt.contains(kingfisher.name), prompt)
    }

    @Test
    fun 天候不明で確定した行は渡さない() {
        val unknown = SessionWeather(1L, WeatherCondition.UNKNOWN, null, fetchedAtMs = 1L)

        assertNull(facts(review(weather = unknown)).weather)
        assertFalse(
            WalkReviewRemarkPromptBuilder.userPrompt(facts(review(weather = unknown)))
                .contains("天候"),
        )
    }

    @Test
    fun 同じ散歩なら同じプロンプトになる() {
        // 指紋（PromptHash）が揺れるとキャッシュが当たらず、毎回課金される
        val first = WalkReviewRemarkPromptBuilder.request(facts(review()), maxTokens = 400)
        val second = WalkReviewRemarkPromptBuilder.request(facts(review()), maxTokens = 400)

        assertEquals(first.promptHash(), second.promptHash())
    }

    @Test
    fun 天候が後から埋まると指紋が変わる() {
        // 天候込みで生成された一言を、天候なしの指紋で「未生成」と誤判定しないための性質
        val withoutWeather = WalkReviewRemarkPromptBuilder.request(facts(review()), maxTokens = 400)
        val withWeather = WalkReviewRemarkPromptBuilder.request(
            facts(review(weather = SessionWeather(1L, WeatherCondition.CLEAR, 18.0, 1L))),
            maxTokens = 400,
        )

        assertFalse(withoutWeather.promptHash() == withWeather.promptHash())
    }

    @Test
    fun 切り口は1つだけ指示する() {
        // 禁止リストを並べない（RemarkAngle のKDoc「禁止リストではなく1値を渡す」）
        val prompt = WalkReviewRemarkPromptBuilder.userPrompt(
            facts(review(), context(angle = RemarkAngle.MEMORY)),
        )

        assertTrue(prompt.contains("今日はこの切り口を中心に書く: この道の記憶"), prompt)
        assertFalse(prompt.contains("MEMORY"), "内部の値（utterance_log.angle）は渡さない")
        assertEquals(
            1,
            prompt.lines().count { it.startsWith("今日はこの切り口") },
            "切り口の指示は1行だけ",
        )
    }

    @Test
    fun 記憶は道も日数も抽象化して渡る() {
        val prompt = WalkReviewRemarkPromptBuilder.userPrompt(
            facts(
                review = review(),
                context = context(
                    angle = RemarkAngle.MEMORY,
                    memory = WalkMemory(WalkMemoryDepth.MONTH_AGO, daysAgo = 43),
                ),
            ),
        )

        assertTrue(prompt.contains("この道はひと月ほど前にも歩いた"), prompt)
        assertFalse(prompt.contains("43"), "日数の生の値は渡さない: $prompt")
        assertFalse(prompt.contains("way"), "内部キー（place_ref）は渡さない: $prompt")
    }

    @Test
    fun 記憶が深いほど言い方が変わる() {
        fun line(depth: WalkMemoryDepth, daysAgo: Int) = WalkReviewRemarkPromptBuilder
            .userPrompt(facts(review(), context(memory = WalkMemory(depth, daysAgo))))
            .lines()
            .single { it.startsWith("この道の記憶") }

        assertTrue(line(WalkMemoryDepth.FIRST_VISIT, 400).contains("初めて歩いたのは一年ほど前"))
        assertTrue(line(WalkMemoryDepth.SEASON_AGO, 95).contains("二、三ヶ月前"))
        assertTrue(line(WalkMemoryDepth.RECENT, 1).contains("昨日"))
    }

    @Test
    fun 歩数は粗い量だけ渡して数値は渡さない() {
        val prompt = WalkReviewRemarkPromptBuilder.userPrompt(
            facts(
                review = review(),
                context = context(
                    angle = RemarkAngle.STEPS,
                    stepsMention = StepsMention(StepsAmount.GOOD, daysAgo = 1),
                ),
            ),
        )

        assertTrue(prompt.contains("昨日、けっこう歩いた"), prompt)
        listOf("8200", "8,200", "歩数").forEach { forbidden ->
            assertFalse(prompt.contains(forbidden), "$forbidden がプロンプトに乗っている: $prompt")
        }
    }

    @Test
    fun ブランクに触れないよう指示する() {
        // 事実として渡す口が無い（WalkReviewRemarkFacts のKDoc）うえで、言い方も禁じる
        val system = WalkReviewRemarkPromptBuilder.systemPrompt

        assertTrue(system.contains("歩かなかった日・散歩の間隔・空いた期間には一切触れない"), system)
        assertTrue(system.contains("久しぶり"), system)
        assertTrue(system.contains("催促しない"), system)
    }

    @Test
    fun 切り口が変わると指紋が変わる() {
        // 同じ散歩でも切り口が回れば別の一言になる＝キャッシュも別扱いでなければならない
        val distance = WalkReviewRemarkPromptBuilder
            .request(facts(review(), context(angle = RemarkAngle.DISTANCE)), maxTokens = 400)
        val timeOfDay = WalkReviewRemarkPromptBuilder
            .request(facts(review(), context(angle = RemarkAngle.TIME_OF_DAY)), maxTokens = 400)

        assertFalse(distance.promptHash() == timeOfDay.promptHash())
    }

    @Test
    fun 応答から本文を取り出す() {
        val raw = """前置き {"remark": "おかえり。今日はよく歩いたね。"} """

        assertEquals("おかえり。今日はよく歩いたね。", WalkReviewRemarkResponseParser.parse(raw))
    }

    @Test
    fun 壊れた応答はnull() {
        assertNull(WalkReviewRemarkResponseParser.parse("""{"flavor": "キーが違う"}"""))
        assertNull(WalkReviewRemarkResponseParser.parse("""{"remark": "途中で切れ"""), "閉じ引用符が無い")
        assertNull(WalkReviewRemarkResponseParser.parse("""{"remark": "   "}"""), "空白だけ")
    }

    @Test
    fun 定型文は散歩の内容で変わる() {
        val quiet = WalkReviewRemarkFallback.text(facts(review()))
        val grown = WalkReviewRemarkFallback.text(
            facts(review(grownWays = listOf(ReviewGrownWay(1L, null, GrowthStage.SHRUB, GrowthStage.TREE)))),
        )
        val discovered = WalkReviewRemarkFallback.text(facts(review(discovered = listOf(kingfisher))))

        assertEquals(3, setOf(quiet, grown, discovered).size, "3通りが別の文になる")
        listOf(quiet, grown, discovered).forEach { text ->
            assertFalse(text.contains("2.3"), "定型文に事実（数字）を含めない: $text")
            assertFalse(text.contains(kingfisher.name), "定型文に事実（種名）を含めない: $text")
        }
    }
}

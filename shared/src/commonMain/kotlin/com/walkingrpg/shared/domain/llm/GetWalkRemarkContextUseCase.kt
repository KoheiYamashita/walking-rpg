package com.walkingrpg.shared.domain.llm

import com.walkingrpg.shared.domain.matching.PassageRepository
import com.walkingrpg.shared.domain.review.TimeOfDay
import com.walkingrpg.shared.domain.review.TimeOfDayResolver
import com.walkingrpg.shared.domain.review.WalkReview
import com.walkingrpg.shared.domain.steps.CalendarDay
import com.walkingrpg.shared.domain.steps.CalendarDays
import com.walkingrpg.shared.domain.steps.StepImportRepository
import com.walkingrpg.shared.domain.walk.WalkSessionRepository

/**
 * 一言の材料のうち、[WalkReview] に入っていないぶんを調達する（**通信しない**）。
 *
 * ```
 * passage の訪問履歴  → この道の記憶（WalkMemoryCalculator）
 * step_import × walk_session → 押し忘れの日への言及（StepsMention）
 * utterance_log       → その場所で使った切り口 → 今日の切り口（RemarkAngleSelector）
 * ```
 *
 * ## 生成側と読み側が同じ1本を通る（この設計の要）
 * [GenerateWalkReviewRemarkUseCase]（作る側）と [ObserveWalkReviewRemarkUseCase]（読む側）は
 * **どちらもここを呼ぶ**。読み側は同じ事実から同じプロンプト指紋（[PromptHash]）を
 * 計算し直して `llm_cache` と突き合わせるので、両者の材料が1文字でも違えば
 * 「生成済みなのに未生成」と判定され、定型文を出したうえで作り直す
 * ＝**毎回課金する**ループに落ちる。
 *
 * ## 材料は「そのセッションの時刻から見た過去」だけ
 * 上と同じ理由で、**現在時刻を材料にしない**。時間が経つと変わる事実を1つでも入れると、
 * 同じ散歩の指紋が日を追って変わる：
 * - 記憶の日数は `clock.nowMillis()` ではなく [WalkReview.startedAtMs] 基準
 * - `utterance_log` の除外集合は「`said_at` がこの散歩の開始より前」かつ「別のセッション」に固定
 * - 歩数の言及はその散歩の**日より前**の確定した日だけ（当日は対象外）
 *
 * ## ブランク（歩かなかった期間）は材料に入れない
 * 「前回から何日空いたか」は**構造的に渡さない**（この文脈に入れる場所を作らない）。
 * 禁止文（[WalkReviewRemarkPromptBuilder.systemPrompt]）で「触れるな」と書くだけでは、
 * 材料があれば滲む。design.md §2「負のフレームを作らない」・§5「責めない」を
 * プロンプトの言葉ではなくデータの形で守る。
 */
class GetWalkRemarkContextUseCase(
    private val walkSessionRepository: WalkSessionRepository,
    private val passageRepository: PassageRepository,
    private val stepImportRepository: StepImportRepository,
    private val utteranceLogRepository: UtteranceLogRepository,
    private val timeOfDayResolver: TimeOfDayResolver,
    private val calendarDays: CalendarDays,
    private val config: WalkRemarkConfig = WalkRemarkConfig.DEFAULT,
) {
    suspend operator fun invoke(review: WalkReview): WalkRemarkContext {
        val memory = WalkMemoryCalculator.memory(
            sessionId = review.sessionId,
            sessionStartedAtMs = review.startedAtMs,
            sessionVisitsByWay = passageRepository.sessionVisitsByWay(),
            calendarDays = calendarDays,
            config = config,
        )
        val stepsMention = stepsMention(review)
        val placeRef = placeRef(review, memory)

        val lastUsedAtMs = utteranceLogRepository
            .recentUtterances(
                placeRef = placeRef,
                beforeMs = review.startedAtMs,
                excludeSessionId = review.sessionId,
                limit = config.utteranceHistoryLimit,
            )
            // 同じ切り口が複数あれば新しい方を残す（「いちばん古く使った」の再利用判定用）。
            .groupBy { it.angle }
            .mapValues { (_, records) -> records.maxOf { it.saidAtMs } }

        return WalkRemarkContext(
            timeOfDay = timeOfDayResolver.timeOfDay(review.startedAtMs),
            angle = RemarkAngleSelector.select(
                available = RemarkAngleSelector.availableAngles(
                    review = review,
                    memory = memory?.memory,
                    stepsMention = stepsMention,
                ),
                lastUsedAtMs = lastUsedAtMs,
            ),
            placeRef = placeRef,
            memory = memory?.memory,
            stepsMention = stepsMention,
        )
    }

    /**
     * 発話履歴のキー。
     *
     * 「その場所についてこの切り口はもう使った」を効かせる単位で、
     * 発見があった散歩は種、記憶を語れる散歩はその道、どちらも無ければ日単位に落ちる。
     * `day` に落ちた散歩どうしは切り口を共有する＝いつもの散歩が続くほど切り口が回る
     * （単調化対策が効くのはまさにそこ。design.md §5）。
     */
    private fun placeRef(review: WalkReview, memory: WalkMemoryReference?): String = when {
        // 種のIDはカタログの手書きID（OSMのIDでも位置でもない）。プロンプトには出さない。
        review.discoveredSpecies.isNotEmpty() ->
            "species:${review.discoveredSpecies.minOf { it.id }}"

        memory != null -> "way:${memory.wayId}"
        else -> "day"
    }

    /**
     * 「歩数だけ残っている日」への言及。
     *
     * 対象は**その散歩の日より前**で、`step_import` に行があり、
     * その日に `walk_session` が1件もない日（＝開始を押し忘れた日）。
     * 複数あればいちばん近い日を採る（古い日を持ち出すほど「記録の指摘」に近づく）。
     */
    private suspend fun stepsMention(review: WalkReview): StepsMention? {
        val sessionDay = calendarDays.day(review.startedAtMs)

        val candidates = stepImportRepository.stepImports()
            .filter { it.steps >= config.stepsMentionMinSteps }
            .mapNotNull { stepImport ->
                val daysAgo = calendarDays.daysBetween(stepImport.day, sessionDay)
                if (daysAgo !in 1..config.stepsMentionMaxDaysAgo) return@mapNotNull null
                stepImport.day to StepsMention.of(
                    steps = stepImport.steps,
                    daysAgo = daysAgo,
                    config = config,
                )
            }
        if (candidates.isEmpty()) return null

        val walkedDays = walkedDays(review.startedAtMs)
        return candidates
            .filterNot { (day, _) -> day in walkedDays }
            .minByOrNull { (_, mention) -> mention.daysAgo }
            ?.second
    }

    /** 直近（言及しうる範囲）で散歩の記録がある日。 */
    private suspend fun walkedDays(sessionStartedAtMs: Long): Set<CalendarDay> {
        // 暦日の境界はタイムゾーン依存なので、日数ぶんきっちりではなく1日ぶん広く引いて
        // 暦日に直してから突き合わせる（範囲の端の日が漏れないようにするため）。
        val windowMs = (config.stepsMentionMaxDaysAgo + 1).toLong() * MILLIS_PER_DAY
        return walkSessionRepository
            .sessionsStartedBetween(
                fromMs = sessionStartedAtMs - windowMs,
                untilMs = sessionStartedAtMs,
            )
            .mapTo(mutableSetOf()) { calendarDays.day(it.startedAtMs) }
    }

    private companion object {
        const val MILLIS_PER_DAY: Long = 24 * 60 * 60 * 1_000L
    }
}

/**
 * [WalkReview] の外から集めた一言の材料。
 *
 * [WalkReviewRemarkFacts.of] の第2引数で、**生成側と読み側で同じ値になることが
 * キャッシュの前提**（[GetWalkRemarkContextUseCase] のKDoc）。
 *
 * @param timeOfDay 出発時刻の時間帯（`TimeOfDayResolver` が出す）。
 * @param angle 今日の切り口（[RemarkAngleSelector] が決めた1つ）。
 * @param placeRef 発話履歴のキー。**プロンプトには出さない**内部キーで、
 *  `utterance_log` に書くためだけに持ち歩く。
 * @param memory この道の記憶（無ければ `null`＝触れさせない）。
 * @param stepsMention 歩数だけ残っている日への言及（無ければ `null`）。
 */
data class WalkRemarkContext(
    val timeOfDay: TimeOfDay,
    val angle: RemarkAngle,
    val placeRef: String,
    val memory: WalkMemory? = null,
    val stepsMention: StepsMention? = null,
)

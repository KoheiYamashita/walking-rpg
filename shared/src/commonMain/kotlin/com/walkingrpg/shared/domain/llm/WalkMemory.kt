package com.walkingrpg.shared.domain.llm

import com.walkingrpg.shared.domain.matching.SessionVisit
import com.walkingrpg.shared.domain.steps.CalendarDays

/**
 * パートナーが参照する「この道の記憶」（design.md §5「記憶を持つ」）。
 *
 * > 「ここ、初めて来た日は何もなかったね」
 * > 時間が経つほど参照する記憶を深くする（昨日 → 1ヶ月前 → 季節前 → 初回）
 *
 * 材料は `passage` の集計（[SessionVisit]）だけ＝**事実は歩行ログから渡す**ので、
 * ハルシネーションが構造的に起きない（design.md §5）。
 */

/**
 * 記憶の深さ。**enumの並び順が深さの優先順位**（上が深い＝優先）。
 *
 * 深いほうを優先するのは design.md §5 の「時間が経つほど参照する記憶を深くする」の実装で、
 * 通い続けた道ほど古い記憶が使えるようになる＝続けた人にだけ出る話材が増える。
 */
enum class WalkMemoryDepth {
    /** この道を初めて歩いた日（[WalkRemarkConfig.memoryFirstVisitMinDaysAgo] より古い）。 */
    FIRST_VISIT,

    /** 季節前にも歩いた（[WalkRemarkConfig.memorySeasonMinDaysAgo] より古い）。 */
    SEASON_AGO,

    /** ひと月ほど前にも歩いた（[WalkRemarkConfig] の 25〜45日）。 */
    MONTH_AGO,

    /**
     * 最近も歩いた（昨日・数日前）。
     *
     * 上の3つに当てはまらない過去の訪問は全部ここに落ちる（例：2週間前だけ歩いた道）。
     * 「深い記憶が無いから記憶に触れない」にはしない：浅い記憶でも
     * 「この道、また来たね」は成立するし、無言よりよい。
     */
    RECENT,
}

/**
 * プロンプトに載せる記憶1件。
 *
 * ## 抽象度
 * 道の名前・座標・場所名は入れない（[WalkReviewRemarkFacts] のKDoc「位置情報は渡さない」を
 * 記憶にもそのまま適用する）。入るのは「この道」「どれくらい前か」だけ。
 * [daysAgo] をそのまま文章に出さないのはプロンプトビルダーの仕事
 * （「ひと月ほど前」のような粗い言い方に丸める）。
 *
 * @param depth どの深さの記憶か。
 * @param daysAgo その散歩の日から見て何日前か（1以上）。
 */
data class WalkMemory(
    val depth: WalkMemoryDepth,
    val daysAgo: Int,
) {
    init {
        require(daysAgo >= 1) { "記憶は「その散歩の日より前」だけ: $daysAgo" }
    }
}

/**
 * 選んだ記憶と、その相手の道。
 *
 * [wayId] は `utterance_log.place_ref`（`way:<id>`）を作るための**内部キー**で、
 * プロンプトには出さない。
 */
data class WalkMemoryReference(
    val wayId: Long,
    val memory: WalkMemory,
)

/**
 * そのセッションで通った道の訪問履歴から、記憶を1件選ぶ**純関数**。
 *
 * ## そのセッションの時刻から見た過去だけ
 * 基準は `clock.nowMillis()` ではなく **`walk_session.started_at`**。
 * 現在時刻を基準にすると、同じ散歩でも日が経つだけで「N日前」が変わり、
 * プロンプト指紋（[PromptHash]）が変わる＝生成済みの一言が「未生成」に見えて
 * 毎回作り直される（[GetWalkRemarkContextUseCase] のKDoc「指紋の安定」）。
 *
 * 追加のI/Oは要らない：材料は図鑑（`codex_progress`）が使うのと同じ
 * `PassageRepository.sessionVisitsByWay()` 1本。
 */
object WalkMemoryCalculator {

    /**
     * @param sessionId 振り返り対象の散歩。この散歩自身の訪問は記憶に数えない。
     * @param sessionStartedAtMs その散歩の開始時刻（基準の時刻）。
     * @param sessionVisitsByWay 全セッションの訪問（道 → その道を通った散歩）。
     * @param calendarDays 暦日の計算（`daysBetween`）。純関数から時計は読まない
     *  ＝ここに渡すのは変換だけを行う口（[CalendarDays.day]）。
     * @return 選んだ記憶。過去の訪問がある道が1本も無ければ `null`
     *  （初めての散歩・初めて歩く道だけの散歩では記憶に触れない）。
     */
    fun memory(
        sessionId: Long,
        sessionStartedAtMs: Long,
        sessionVisitsByWay: Map<Long, List<SessionVisit>>,
        calendarDays: CalendarDays,
        config: WalkRemarkConfig = WalkRemarkConfig.DEFAULT,
    ): WalkMemoryReference? {
        val sessionDay = calendarDays.day(sessionStartedAtMs)

        val candidates = sessionVisitsByWay.mapNotNull { (wayId, visits) ->
            // この散歩で通っていない道の記憶は語らない（今日の話題ではない）。
            if (visits.none { it.sessionId == sessionId }) return@mapNotNull null

            val pastDaysAgo = visits
                .filter { it.sessionId != sessionId && it.firstTimestampMs < sessionStartedAtMs }
                .map { calendarDays.daysBetween(calendarDays.day(it.firstTimestampMs), sessionDay) }
                // 同じ日の別セッションは「記憶」ではない（今日の続き）。
                .filter { it >= 1 }
            val memory = memoryOf(pastDaysAgo, config) ?: return@mapNotNull null
            WalkMemoryReference(wayId = wayId, memory = memory)
        }

        // 深い順 → 古い順 → way ID順。最後のway IDまで固定するのは、
        // 同じ深さ・同じ日数の道が2本あったときに選択が揺れないようにするため（決定的）。
        return candidates.minWithOrNull(
            compareBy(
                { it.memory.depth.ordinal },
                { -it.memory.daysAgo },
                { it.wayId },
            ),
        )
    }

    /** 1本の道の過去の訪問（何日前か）から、いちばん深い記憶を決める。 */
    private fun memoryOf(pastDaysAgo: List<Int>, config: WalkRemarkConfig): WalkMemory? {
        if (pastDaysAgo.isEmpty()) return null
        val firstDaysAgo = pastDaysAgo.max()

        // 「初めて歩いた日」は、いちばん古い訪問がそれ自体で語れる古さのときだけ。
        if (firstDaysAgo >= config.memoryFirstVisitMinDaysAgo) {
            return WalkMemory(WalkMemoryDepth.FIRST_VISIT, firstDaysAgo)
        }
        if (firstDaysAgo >= config.memorySeasonMinDaysAgo) {
            return WalkMemory(WalkMemoryDepth.SEASON_AGO, firstDaysAgo)
        }
        pastDaysAgo
            .filter { it in config.memoryMonthMinDaysAgo..config.memoryMonthMaxDaysAgo }
            .maxOrNull()
            ?.let { return WalkMemory(WalkMemoryDepth.MONTH_AGO, it) }

        // 深い記憶が無い道は、いちばん近い訪問を「最近も歩いた」として使う。
        return WalkMemory(WalkMemoryDepth.RECENT, pastDaysAgo.min())
    }
}

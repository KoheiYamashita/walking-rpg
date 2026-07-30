package com.walkingrpg.shared.domain.llm

import com.walkingrpg.shared.domain.matching.SessionVisit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 記憶の選び方（[WalkMemoryCalculator]・design.md §5「時間が経つほど参照する記憶を深くする」）。
 *
 * 見たいのは3つ：
 * - 深さの優先順（初回 ＞ 季節前 ＞ ひと月前 ＞ 最近）
 * - **基準はそのセッションの開始時刻**（現在時刻を変えても結果が動かない＝指紋が安定する）
 * - 初めて歩く道だけの散歩では記憶に触れない
 */
class WalkMemoryCalculatorTest {

    private val calendarDays = UtcCalendarDays()

    /** 振り返り対象の散歩（2026-07-30 の散歩）。 */
    private val sessionId = 100L
    private val sessionStartedAtMs = noonUtcMs("2026-07-30")

    /**
     * その道の訪問履歴を作る。
     *
     * @param days この散歩自身のぶんを除いた「過去に歩いた日」。
     */
    private fun visits(vararg days: String): List<SessionVisit> =
        listOf(SessionVisit(sessionId = sessionId, firstTimestampMs = sessionStartedAtMs)) +
            days.mapIndexed { index, day ->
                SessionVisit(sessionId = index + 1L, firstTimestampMs = noonUtcMs(day))
            }

    private fun memory(
        sessionVisitsByWay: Map<Long, List<SessionVisit>>,
        startedAtMs: Long = sessionStartedAtMs,
    ) = WalkMemoryCalculator.memory(
        sessionId = sessionId,
        sessionStartedAtMs = startedAtMs,
        sessionVisitsByWay = sessionVisitsByWay,
        calendarDays = calendarDays,
    )

    @Test
    fun 初めて歩いた日が古ければ初回の記憶になる() {
        // 2025-01-05 ＝ 半年より前（WalkRemarkConfig.memoryFirstVisitMinDaysAgo）
        val result = memory(mapOf(1L to visits("2025-01-05", "2026-07-29")))

        assertEquals(WalkMemoryDepth.FIRST_VISIT, result?.memory?.depth)
        assertEquals(571, result?.memory?.daysAgo, "初回の記憶はいちばん古い訪問")
        assertEquals(1L, result?.wayId)
    }

    @Test
    fun 季節前の訪問は季節前の記憶になる() {
        // 100日前＝ひと季節前だが、半年には届かない
        val result = memory(mapOf(1L to visits("2026-04-21")))

        assertEquals(WalkMemoryDepth.SEASON_AGO, result?.memory?.depth)
        assertEquals(100, result?.memory?.daysAgo)
    }

    @Test
    fun ひと月ほど前の訪問はひと月前の記憶になる() {
        val result = memory(mapOf(1L to visits("2026-06-25", "2026-07-29")))

        assertEquals(WalkMemoryDepth.MONTH_AGO, result?.memory?.depth)
        assertEquals(35, result?.memory?.daysAgo)
    }

    @Test
    fun 深い記憶が無ければ最近の訪問を使う() {
        // 「深い記憶が無いから何も言わない」にはしない（無言よりよい。WalkMemoryDepth のKDoc）
        val result = memory(mapOf(1L to visits("2026-07-16", "2026-07-29")))

        assertEquals(WalkMemoryDepth.RECENT, result?.memory?.depth)
        assertEquals(1, result?.memory?.daysAgo, "最近の記憶はいちばん近い訪問")
    }

    @Test
    fun 深い記憶のある道を優先する() {
        val result = memory(
            mapOf(
                // 昨日も歩いた道
                7L to visits("2026-07-29"),
                // ひと月前にも歩いた道
                8L to visits("2026-06-25"),
                // 季節前から歩いている道
                9L to visits("2026-04-01"),
            ),
        )

        assertEquals(9L, result?.wayId, "いちばん深い記憶の道を選ぶ")
        assertEquals(WalkMemoryDepth.SEASON_AGO, result?.memory?.depth)
    }

    @Test
    fun 初めての散歩では記憶がない() {
        val result = memory(mapOf(1L to visits()))

        assertNull(result, "過去の訪問が無い道しか歩いていない")
    }

    @Test
    fun この散歩で通っていない道の記憶は語らない() {
        val other = listOf(
            SessionVisit(sessionId = 1L, firstTimestampMs = noonUtcMs("2025-01-05")),
        )

        assertNull(memory(mapOf(2L to other)), "今日の話題ではない")
    }

    @Test
    fun 同じ日の別セッションは記憶に数えない() {
        val sameDay = listOf(
            SessionVisit(sessionId = sessionId, firstTimestampMs = sessionStartedAtMs),
            SessionVisit(sessionId = 99L, firstTimestampMs = sessionStartedAtMs - 60_000L),
        )

        assertNull(memory(mapOf(1L to sameDay)), "今日の続きは「記憶」ではない")
    }

    @Test
    fun 基準は現在時刻ではなくセッションの開始時刻() {
        // これが崩れると、同じ散歩なのに日が経つだけでプロンプト指紋が変わる
        // （＝毎回定型文＋再生成の課金ループ。GetWalkRemarkContextUseCase のKDoc）
        val visitsByWay = mapOf(1L to visits("2026-06-25"))

        // 現在時刻の入口（UtcCalendarDays.today）を踏むと落ちるフェイクを使っているので、
        // 「今日」を読む実装ならこの呼び出し自体が失敗する
        assertEquals(35, memory(visitsByWay)?.memory?.daysAgo, "開始時刻から数える")

        // 別の日に出発した散歩なら、同じ履歴でも日数は違う（＝基準はその散歩の時刻）
        assertEquals(
            5,
            memory(visitsByWay, startedAtMs = noonUtcMs("2026-06-30"))?.memory?.daysAgo,
        )
    }

    @Test
    fun 同じ深さなら古い記憶を選び最後はway順で決まる() {
        val result = memory(
            mapOf(
                3L to visits("2026-06-20"),
                2L to visits("2026-06-20"),
                4L to visits("2026-06-25"),
            ),
        )

        assertEquals(40, result?.memory?.daysAgo, "同じ深さなら古い方")
        assertEquals(2L, result?.wayId, "同じ深さ・同じ日数ならway ID順（決定的）")
    }
}

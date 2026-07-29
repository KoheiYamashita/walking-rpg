package com.walkingrpg.shared.domain.feedback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 振動のレート制限（design.md §5「1回の散歩で2〜3回だけ」）。 */
class VibrationBudgetTest {

    /** 間隔の縛りを外して「回数の上限」だけを見るための設定。 */
    private fun countOnly(max: Int) = WalkFeedbackConfig(
        maxVibrationsPerWalk = max,
        minVibrationIntervalMs = 0L,
    )

    @Test
    fun 上限まで振動して以降は無音になる() {
        var budget = VibrationBudget(countOnly(max = 3))
        val vibrated = mutableListOf<Boolean>()

        repeat(5) { index ->
            val decision = budget.eventOccurred(atMs = index * 1_000L)
            budget = decision.budget
            vibrated += decision.shouldVibrate
        }

        assertEquals(listOf(true, true, true, false, false), vibrated)
        assertEquals(3, budget.vibratedCount)
    }

    @Test
    fun 既定の上限は3回まで() {
        var budget = VibrationBudget()
        var vibrations = 0

        // 30分の散歩で10件のイベントが出た想定（10分ごと＝既定の最小間隔は満たす）
        repeat(10) { index ->
            val decision = budget.eventOccurred(atMs = index * 600_000L)
            budget = decision.budget
            if (decision.shouldVibrate) vibrations++
        }

        assertTrue(vibrations in 2..3, "1散歩の振動は2〜3回まで: $vibrations")
    }

    @Test
    fun 前の振動から間隔が空くまで振動しない() {
        val config = WalkFeedbackConfig(maxVibrationsPerWalk = 3, minVibrationIntervalMs = 60_000L)
        var budget = VibrationBudget(config)

        val first = budget.eventOccurred(atMs = 0L).also { budget = it.budget }
        val tooSoon = budget.eventOccurred(atMs = 59_999L).also { budget = it.budget }
        val spaced = budget.eventOccurred(atMs = 60_000L).also { budget = it.budget }

        assertTrue(first.shouldVibrate)
        assertFalse(tooSoon.shouldVibrate)
        assertTrue(spaced.shouldVibrate)
        assertEquals(2, budget.vibratedCount)
    }

    @Test
    fun 沈黙したイベントは残数を消費しない() {
        val config = WalkFeedbackConfig(maxVibrationsPerWalk = 2, minVibrationIntervalMs = 60_000L)
        var budget = VibrationBudget(config)

        budget = budget.eventOccurred(atMs = 0L).budget
        // 間隔が足りずに沈黙したイベントが何件続いても……
        repeat(10) { index ->
            budget = budget.eventOccurred(atMs = 1_000L + index * 1_000L).budget
        }
        val spaced = budget.eventOccurred(atMs = 60_000L)

        // ……間隔が空けばちゃんと鳴る（残数を食い潰さない）
        assertTrue(spaced.shouldVibrate)
        assertEquals(2, spaced.budget.vibratedCount)
    }

    @Test
    fun 時刻が巻き戻っても余分に振動しない() {
        val config = WalkFeedbackConfig(maxVibrationsPerWalk = 3, minVibrationIntervalMs = 60_000L)
        var budget = VibrationBudget(config)

        budget = budget.eventOccurred(atMs = 600_000L).budget
        val rewound = budget.eventOccurred(atMs = 0L)

        assertFalse(rewound.shouldVibrate)
    }

    @Test
    fun 上限0なら一度も振動しない() {
        val decision = VibrationBudget(countOnly(max = 0)).eventOccurred(atMs = 0L)

        assertFalse(decision.shouldVibrate)
    }
}

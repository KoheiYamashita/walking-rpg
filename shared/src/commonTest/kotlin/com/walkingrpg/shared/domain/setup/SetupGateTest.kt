package com.walkingrpg.shared.domain.setup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 進行判定の検証。決定事項（design.md §9）が壊れていないことをここで守る：
 * **疎通が通るまで先へ進めない／対象圏が無いと完了できない**。
 */
class SetupGateTest {

    @Test
    fun ようこそは何もしなくても次へ進める() {
        assertTrue(SetupGate.canAdvance(SetupStep.WELCOME, SetupProgress()))
    }

    @Test
    fun 疎通が通るまでLLMのステップから進めない() {
        val notVerified = SetupProgress(llmVerified = false)
        assertFalse(SetupGate.canAdvance(SetupStep.LLM, notVerified))

        val verified = SetupProgress(llmVerified = true)
        assertTrue(SetupGate.canAdvance(SetupStep.LLM, verified))
    }

    @Test
    fun 天候は既定のままなら進める() {
        assertTrue(SetupGate.canAdvance(SetupStep.WEATHER, SetupProgress(weatherReady = true)))
    }

    @Test
    fun 天候はキーが要るのに未入力なら進めない() {
        assertFalse(SetupGate.canAdvance(SetupStep.WEATHER, SetupProgress(weatherReady = false)))
    }

    @Test
    fun 自宅登録は任意なのでスキップできる() {
        // 測位できない場所でウィザードが詰まないようにするための仕様
        assertFalse(SetupProgress().homeRegistered)
        assertTrue(SetupGate.canAdvance(SetupStep.HOME, SetupProgress(homeRegistered = false)))
    }

    @Test
    fun 対象圏を取り込むまで先へ進めない() {
        assertFalse(SetupGate.canAdvance(SetupStep.AREA, SetupProgress(areaImported = false)))
        assertTrue(SetupGate.canAdvance(SetupStep.AREA, SetupProgress(areaImported = true)))
    }

    @Test
    fun 完了条件は疎通と取り込みの両方() {
        assertFalse(SetupGate.isComplete(SetupProgress()))
        assertFalse(SetupGate.isComplete(SetupProgress(llmVerified = true)))
        assertFalse(SetupGate.isComplete(SetupProgress(areaImported = true)))
        assertTrue(
            SetupGate.isComplete(SetupProgress(llmVerified = true, areaImported = true)),
        )
    }

    @Test
    fun 自宅未登録でも完了できる() {
        val progress = SetupProgress(
            llmVerified = true,
            areaImported = true,
            homeRegistered = false,
        )
        assertTrue(SetupGate.isComplete(progress))
    }

    @Test
    fun ステップは前後に辿れて端で止まる() {
        assertEquals(SetupStep.LLM, SetupGate.next(SetupStep.WELCOME))
        assertEquals(SetupStep.WELCOME, SetupGate.previous(SetupStep.LLM))
        assertNull(SetupGate.next(SetupStep.DONE))
        assertNull(SetupGate.previous(SetupStep.WELCOME))
    }
}

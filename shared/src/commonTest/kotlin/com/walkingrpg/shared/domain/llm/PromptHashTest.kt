package com.walkingrpg.shared.domain.llm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * プロンプトの指紋（[PromptHash]）。
 *
 * 暗号強度は要らないが、**同じ入力なら常に同じ値**でなければキャッシュが毎回作り直される。
 * 期待値をハードコードしないのは、実装（FNV-1a）を差し替える余地を残すため：
 * ここで見るのは性質だけ。
 */
class PromptHashTest {

    @Test
    fun 同じ入力なら同じ指紋() {
        assertEquals(PromptHash.of("system", "user"), PromptHash.of("system", "user"))
    }

    @Test
    fun 入力が違えば指紋も違う() {
        assertNotEquals(PromptHash.of("system", "user"), PromptHash.of("system", "user!"))
    }

    @Test
    fun 部品の切れ目が変われば指紋も変わる() {
        // 区切りを入れていないと ("ab","c") と ("a","bc") が同じ指紋になる
        assertNotEquals(PromptHash.of("ab", "c"), PromptHash.of("a", "bc"))
    }

    @Test
    fun 部品の順番も見る() {
        assertNotEquals(PromptHash.of("a", "b"), PromptHash.of("b", "a"))
    }

    @Test
    fun 桁数は一定() {
        val hashes = listOf(
            PromptHash.of(""),
            PromptHash.of("a"),
            PromptHash.of("日本語のプロンプト"),
            PromptHash.of("system", "user"),
        )

        hashes.forEach { hash ->
            assertEquals(16, hash.length, hash)
            assertTrue(hash.all { it in "0123456789abcdef" }, hash)
        }
    }
}

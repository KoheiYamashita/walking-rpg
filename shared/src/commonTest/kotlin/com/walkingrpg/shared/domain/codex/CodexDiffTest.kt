package com.walkingrpg.shared.domain.codex

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 再計算の前後差分（[CodexDiff]）。図鑑の「今回出た」の出どころ。
 */
class CodexDiffTest {

    private fun progress(
        speciesId: String,
        visitCount: Int = 3,
        discoveredAtMs: Long? = null,
        foreshadowStage: ForeshadowStage = ForeshadowStage.NONE,
    ) = CodexProgress(
        speciesId = speciesId,
        visitCount = visitCount,
        foreshadowStage = foreshadowStage,
        discoveredAtMs = discoveredAtMs,
    )

    @Test
    fun 未発見から発見になった種が出る() {
        val before = listOf(progress("a", foreshadowStage = ForeshadowStage.NEAR))
        val after = listOf(progress("a", discoveredAtMs = 100L))

        assertEquals(setOf("a"), CodexDiff.newlyDiscoveredSpeciesIds(before, after))
    }

    @Test
    fun 行が無かった種も新規発見に含める() {
        // 初めて訪問した棚では、行が生えたときにすでに発見済みになりうる
        val after = listOf(progress("a", discoveredAtMs = 100L))

        assertEquals(setOf("a"), CodexDiff.newlyDiscoveredSpeciesIds(before = emptyList(), after = after))
    }

    @Test
    fun 前から発見済みの種は出ない() {
        val before = listOf(progress("a", discoveredAtMs = 100L))
        val after = listOf(progress("a", visitCount = 5, discoveredAtMs = 100L))

        assertEquals(emptySet(), CodexDiff.newlyDiscoveredSpeciesIds(before, after))
    }

    @Test
    fun 予兆が進んだだけでは新規発見にならない() {
        val before = listOf(progress("a", visitCount = 1))
        val after = listOf(progress("a", visitCount = 2, foreshadowStage = ForeshadowStage.NEAR))

        assertEquals(emptySet(), CodexDiff.newlyDiscoveredSpeciesIds(before, after))
    }

    @Test
    fun 行が消えても負の情報は返さない() {
        // 対象圏を狭めて再計算した状況。取り消しではなく「新規発見なし」になるだけ
        val before = listOf(progress("a", discoveredAtMs = 100L))

        assertEquals(emptySet(), CodexDiff.newlyDiscoveredSpeciesIds(before, after = emptyList()))
    }

    @Test
    fun 同時に複数の種が出ることもある() {
        val after = listOf(
            progress("a", discoveredAtMs = 100L),
            progress("b", discoveredAtMs = 200L),
            progress("c", foreshadowStage = ForeshadowStage.NEAR),
        )

        assertEquals(setOf("a", "b"), CodexDiff.newlyDiscoveredSpeciesIds(emptyList(), after))
    }
}

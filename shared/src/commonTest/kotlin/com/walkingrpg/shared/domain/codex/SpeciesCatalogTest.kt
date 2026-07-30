package com.walkingrpg.shared.domain.codex

import com.walkingrpg.shared.domain.growth.GrowthConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 手書きの種カタログ（[SpeciesCatalog]）が design.md §4.4 の設計基準から外れていないこと。
 *
 * 閾値そのもの（3/5/10 …）は固定しない：チューニングでテストが壊れるのは避けて、
 * 守るのは**性質**（`WayGrowthCalculatorTest` と同じ方針）。
 */
class SpeciesCatalogTest {

    @Test
    fun 種のIDは重複しない() {
        // codex_progress と llm_cache の主キーなので、かぶると片方が黙って上書きされる
        assertEquals(SpeciesCatalog.ALL.size, SpeciesCatalog.ALL.map { it.id }.distinct().size)
    }

    @Test
    fun 四本柱の棚には必ず種がある() {
        // design.md §9「図鑑素材を微細アメニティに依存させない。公園・水辺・鉄道・農地の4本柱で組む」
        val pillars = listOf(
            CodexCategory.PARK,
            CodexCategory.WATER,
            CodexCategory.RAILWAY,
            CodexCategory.FARMLAND,
        )

        pillars.forEach { category ->
            assertTrue(
                SpeciesCatalog.byCategory.getValue(category).size >= 2,
                "$category の種が足りない（棚ごとに2〜3種）",
            )
        }
    }

    @Test
    fun どの棚にも種がある() {
        // 空の棚があると GetCodexUseCase がその棚を落として、体系だけが宙に浮く
        CodexCategory.entries.forEach { category ->
            assertTrue(
                SpeciesCatalog.byCategory.getValue(category).isNotEmpty(),
                "$category に種が無い",
            )
        }
    }

    @Test
    fun 出現回数は3回から10回に収まる() {
        // 下限3回：1回では通う意味が消え、2回では予兆を経由できない
        // 上限10回：design.md §4.4「同じ川に10回通って初めて現れる」
        SpeciesCatalog.ALL.forEach { species ->
            assertTrue(
                species.requiredVisitCount in 3..10,
                "${species.id} の閾値が範囲外（${species.requiredVisitCount}）",
            )
        }
    }

    @Test
    fun 図鑑は道の到達点より必ず手前に置く() {
        // GrowthConfig のKDoc：道の「生き物が住みつく」が図鑑より先に来ないようにする
        val creature = GrowthConfig.DEFAULT.creaturePassCount

        SpeciesCatalog.ALL.forEach { species ->
            assertTrue(
                species.requiredVisitCount < creature,
                "${species.id} が道の到達点（$creature 回）より遠い",
            )
        }
    }

    @Test
    fun どの種も必ず予兆を経由して出る() {
        // 閾値 - 先行ぶん が1以上でなければ、予兆が出ないまま突然現れる
        val lead = CodexConfig.DEFAULT.foreshadowLeadVisits

        SpeciesCatalog.ALL.forEach { species ->
            assertTrue(
                species.requiredVisitCount - lead >= 1,
                "${species.id} は予兆を経由できない（閾値 ${species.requiredVisitCount}）",
            )
        }
    }

    @Test
    fun 棚ごとに近い閾値と遠い閾値が混ざっている() {
        // 手近な1種がすぐ埋まって「動いた」が見え、遠い1種が残って通う理由になる
        SpeciesCatalog.byCategory.forEach { (category, inCategory) ->
            val thresholds = inCategory.map { it.requiredVisitCount }
            assertTrue(
                thresholds.distinct().size == thresholds.size,
                "$category に同じ閾値の種がある（$thresholds）",
            )
            assertTrue(
                (thresholds.max() - thresholds.min()) >= 3,
                "$category の閾値が近すぎる（$thresholds）",
            )
        }
    }

    @Test
    fun 予兆の文章に種名を書かない() {
        // 種名が出た時点で残り回数を数字で見せるのと同じことになる（design.md §4.4）
        SpeciesCatalog.ALL.forEach { species ->
            assertTrue(
                !species.foreshadowText.contains(species.name),
                "${species.id} の予兆文に種名が入っている",
            )
        }
    }

    @Test
    fun 名前と予兆文は空でない() {
        SpeciesCatalog.ALL.forEach { species ->
            assertTrue(species.name.isNotBlank(), "${species.id} の名前が空")
            assertTrue(species.foreshadowText.isNotBlank(), "${species.id} の予兆文が空")
        }
    }

    @Test
    fun IDから引ける() {
        val first = SpeciesCatalog.ALL.first()

        assertEquals(first, SpeciesCatalog.species(first.id))
        assertEquals(null, SpeciesCatalog.species("知らないID"))
    }
}

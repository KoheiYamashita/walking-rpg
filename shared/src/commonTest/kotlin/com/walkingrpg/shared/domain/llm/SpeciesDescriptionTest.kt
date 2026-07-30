package com.walkingrpg.shared.domain.llm

import com.walkingrpg.shared.domain.codex.CodexCategory
import com.walkingrpg.shared.domain.codex.SpeciesCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 図鑑の記述文の3点セット（プロンプト／パーサ／定型文）。
 *
 * 見たいのは：
 * - プロンプトに**渡してよい事実だけ**が乗る（座標も地点名も渡さない）
 * - 同じ種なら毎回同じ指紋（＝二度課金しない）
 * - 崩れた応答からは本文を取り出さない（定型文で凌ぐ）
 * - 定型文が全カテゴリに揃っている（図鑑は通信ゼロで開ける）
 */
class SpeciesDescriptionTest {

    private val kingfisher = SpeciesCatalog.species("water_kingfisher")!!

    @Test
    fun 依頼文には名前と棚だけが乗る() {
        val prompt = SpeciesDescriptionPromptBuilder.userPrompt(
            SpeciesDescriptionFacts.of(kingfisher),
        )

        assertEquals(
            """
            名前: カワセミ
            見つかる場所: 水辺（川・池・用水路など）
            """.trimIndent(),
            prompt,
        )
    }

    @Test
    fun システムプロンプトが事実の記述を禁じている() {
        // design.md §4.4「その場所に実際にいるとは主張しない」・§7「事実は書かせない」
        val system = SpeciesDescriptionPromptBuilder.systemPrompt

        listOf("学名", "生態", "分布", "地名", "断定").forEach { forbidden ->
            assertTrue(system.contains(forbidden), "禁止事項に「$forbidden」が無い")
        }
        assertTrue(system.contains("架空の図鑑"), "フィクション扱いを明示する")
        assertTrue(system.contains("""{"description": "本文"}"""), "出力形式を固定する")
    }

    @Test
    fun 同じ種なら毎回同じ指紋になる() {
        val first = speciesPromptHash(kingfisher, LlmGenerationConfig.DEFAULT)
        val second = speciesPromptHash(kingfisher, LlmGenerationConfig.DEFAULT)

        assertEquals(first, second)
    }

    @Test
    fun 出力上限を変えても指紋は変わらない() {
        // 上限は「長く書かせない蓋」であって内容を決める要素ではない（promptHash のKDoc）
        val narrow = LlmGenerationConfig(speciesDescriptionMaxTokens = 100)

        assertEquals(
            speciesPromptHash(kingfisher, LlmGenerationConfig.DEFAULT),
            speciesPromptHash(kingfisher, narrow),
        )
    }

    @Test
    fun 種が違えば指紋が変わる() {
        val owl = SpeciesCatalog.species("shrine_owl")!!

        assertTrue(
            speciesPromptHash(kingfisher, LlmGenerationConfig.DEFAULT) !=
                speciesPromptHash(owl, LlmGenerationConfig.DEFAULT),
        )
    }

    @Test
    fun 応答から本文を取り出せる() {
        assertEquals(
            "水の上を、まっすぐな線が横切る。",
            SpeciesDescriptionResponseParser.parse("""{"description": "水の上を、まっすぐな線が横切る。"}"""),
        )
    }

    @Test
    fun 前置きやコードブロックが付いても取り出せる() {
        val raw = """
            はい、承知しました。
            ```json
            {"description": "静かな水辺にいる。"}
            ```
        """.trimIndent()

        assertEquals("静かな水辺にいる。", SpeciesDescriptionResponseParser.parse(raw))
    }

    @Test
    fun エスケープを解く() {
        assertEquals(
            "「線」が横切る\n静かだ",
            SpeciesDescriptionResponseParser.parse("""{"description": "「線」が横切る\n静かだ"}"""),
        )
    }

    @Test
    fun 途中で切れた応答は読まない() {
        // 出力上限で閉じ引用符に辿り着けない＝半端な文章をキャッシュに焼き付けない
        assertNull(SpeciesDescriptionResponseParser.parse("""{"description": "静かな水辺に"""))
    }

    @Test
    fun キーが無い応答や空の本文は読まない() {
        assertNull(SpeciesDescriptionResponseParser.parse("""{"flavor": "別のキー"}"""))
        assertNull(SpeciesDescriptionResponseParser.parse("""{"description": "   "}"""))
        assertNull(SpeciesDescriptionResponseParser.parse("説明の文章だけが返ってきた"))
    }

    @Test
    fun 定型文は全ての棚に用意されている() {
        CodexCategory.entries.forEach { category ->
            assertTrue(
                SpeciesDescriptionFallback.text(category).isNotBlank(),
                "$category の定型文が無い",
            )
        }
    }

    @Test
    fun 論理キーは種のIDから決まる() {
        assertEquals(
            "SPECIES_DESCRIPTION:species:water_kingfisher",
            llmCacheKey(LlmTaskKind.SPECIES_DESCRIPTION, speciesDescriptionLogicalKey("water_kingfisher")),
        )
        // 地点フレーバーと同じ地物名でも別の行になる（llmCacheKey のKDoc）
        assertTrue(
            llmCacheKey(LlmTaskKind.SPECIES_DESCRIPTION, speciesDescriptionLogicalKey("x")) !=
                llmCacheKey(LlmTaskKind.POI_FLAVOR, poiFlavorLogicalKey("x")),
        )
    }
}

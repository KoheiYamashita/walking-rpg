package com.walkingrpg.shared.domain.llm

import com.walkingrpg.shared.domain.osm.PoiKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 地点フレーバーのプロンプトと応答の後始末。
 *
 * 見たいのは3つ：
 * - **事実を書かせない縛りがプロンプトに入っている**（design.md §7）
 * - プロンプトに位置情報が乗らない（CONTRIBUTING「位置情報の扱い」）
 * - 崩れた応答を「読めた」ことにしない（＝定型文に落ちる）
 */
class PoiFlavorTest {

    @Test
    fun システムプロンプトで事実の創作を禁じる() {
        val prompt = PoiFlavorPromptBuilder.systemPrompt

        assertTrue("実在の場所の事実は書かない" in prompt, prompt)
        assertTrue("歴史" in prompt && "由来" in prompt, prompt)
        // 出力の形が固定でないと、パーサ（PoiFlavorResponseParser）が前提を失う
        assertTrue("\"flavor\"" in prompt, prompt)
    }

    @Test
    fun 依頼文に載るのは種別と名前だけ() {
        val poi = testPoi("node/1", kind = PoiKind.SHRINE, name = "テスト神社")

        val prompt = PoiFlavorPromptBuilder.userPrompt(PoiFlavorFacts.of(poi))

        assertTrue("寺社" in prompt, prompt)
        assertTrue("テスト神社" in prompt, prompt)
        // 座標は渡さない（天候APIに丸めた座標しか送らないのと同じ理由）
        assertTrue("12.3456" !in prompt, prompt)
        assertTrue("34.5678" !in prompt, prompt)
    }

    @Test
    fun 名前が無い地点も依頼できる() {
        val facts = PoiFlavorFacts.of(testPoi("node/2", kind = PoiKind.WATER))

        assertNull(facts.name)
        assertTrue("（無名）" in PoiFlavorPromptBuilder.userPrompt(facts))
    }

    @Test
    fun 空白だけの名前は名前として扱わない() {
        val poi = testPoi("node/3", name = "   ")

        assertNull(PoiFlavorFacts.of(poi).name)
    }

    @Test
    fun JSONから本文を取り出す() {
        val text = PoiFlavorResponseParser.parse("""{"flavor": "木陰が涼しい。"}""")

        assertEquals("木陰が涼しい。", text)
    }

    @Test
    fun 前置きやコードブロックが付いていても取り出せる() {
        val raw = """
            はい、承知しました。
            ```json
            {"flavor": "水面が光を返している。"}
            ```
        """.trimIndent()

        assertEquals("水面が光を返している。", PoiFlavorResponseParser.parse(raw))
    }

    @Test
    fun エスケープを解いて取り出す() {
        val raw = """{"flavor": "「\"ここ\"」は静かだ。\n風が抜ける。。"}"""

        assertEquals("「\"ここ\"」は静かだ。\n風が抜ける。。", PoiFlavorResponseParser.parse(raw))
    }

    @Test
    fun 途中で切れた応答は読めなかったことにする() {
        // 出力上限で打ち切られた応答。半端な文章をキャッシュに焼き付けない
        assertNull(PoiFlavorResponseParser.parse("""{"flavor": "登り切ると空が"""))
    }

    @Test
    fun 想定の形でなければ読めなかったことにする() {
        assertNull(PoiFlavorResponseParser.parse("木陰が涼しい。"), "JSONでない")
        assertNull(PoiFlavorResponseParser.parse("""{"text": "木陰が涼しい。"}"""), "キーが違う")
        assertNull(PoiFlavorResponseParser.parse("""{"flavor": "   "}"""), "空白だけ")
        assertNull(PoiFlavorResponseParser.parse("""{"flavor"}"""), "値が無い")
    }

    @Test
    fun 定型文は全ての種別にある() {
        PoiKind.entries.forEach { kind ->
            val text = PoiFlavorFallback.text(kind)
            assertTrue(text.isNotBlank(), "$kind の定型文が無い")
        }
    }

    @Test
    fun 同じ地点なら依頼の指紋も同じ() {
        val poi = testPoi("node/4", name = "テスト公園")
        val maxTokens = LlmGenerationConfig.DEFAULT.poiFlavorMaxTokens

        val first = PoiFlavorPromptBuilder.request(PoiFlavorFacts.of(poi), maxTokens).promptHash()
        val second = PoiFlavorPromptBuilder.request(PoiFlavorFacts.of(poi), maxTokens).promptHash()

        assertEquals(first, second)
    }

    @Test
    fun 出力上限を変えただけでは指紋が変わらない() {
        // 上限は「長く書かせない蓋」であって文章の内容を決める要素ではない。
        // ここで変わると、上限を触るたびに数百件が作り直し＝再課金になる
        val facts = PoiFlavorFacts.of(testPoi("node/5"))

        assertEquals(
            PoiFlavorPromptBuilder.request(facts, maxTokens = 300).promptHash(),
            PoiFlavorPromptBuilder.request(facts, maxTokens = 500).promptHash(),
        )
    }

    @Test
    fun 種別や名前が変われば指紋も変わる() {
        val maxTokens = LlmGenerationConfig.DEFAULT.poiFlavorMaxTokens
        val park = PoiFlavorFacts.of(testPoi("node/6", kind = PoiKind.PARK, name = "い"))
        val water = PoiFlavorFacts.of(testPoi("node/6", kind = PoiKind.WATER, name = "い"))
        val renamed = PoiFlavorFacts.of(testPoi("node/6", kind = PoiKind.PARK, name = "ろ"))

        val hashes = listOf(park, water, renamed)
            .map { PoiFlavorPromptBuilder.request(it, maxTokens).promptHash() }

        assertEquals(hashes.size, hashes.distinct().size, "同じ指紋になった: $hashes")
        assertNotNull(hashes.first())
    }
}

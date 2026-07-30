package com.walkingrpg.shared.domain.llm

import com.walkingrpg.shared.domain.codex.CodexCategory
import com.walkingrpg.shared.domain.codex.Species

/**
 * 図鑑の記述文（発見した種のページに出す2〜3文）のプロンプトと後始末。
 *
 * `PoiFlavor.kt` と同じ3点セット（プロンプトビルダー／応答パーサ／定型文）で、
 * design.md §7 の「任せる／任せない」も同じ線を引く：
 * **LLMに任せるのは情景と佇まいの文章化だけ**で、生物学の事実や実在の場所の話は書かせない。
 * 何が何回で出るかという骨は手書きのカタログ（`SpeciesCatalog`）が持っている。
 *
 * design.md §4.4「実在度：フィクション扱い」に沿って、
 * **その場所に実際にいるとは主張させない**のがこのプロンプトの主目的。
 * 「戻ってくる」という世界観（design.md §8）が、フィクションであることの免罪符になる。
 *
 * 純Kotlin。JSONの読み取りは [LlmJsonText]（ドメイン層に
 * kotlinx.serialization を持ち込まないため）。
 */

/**
 * プロンプトに載せる事実。**ここに無いものはLLMに渡らない。**
 *
 * 座標も地点名も渡さない：記述文は「その生き物の佇まい」であって場所の説明ではないので、
 * 位置情報を外に出す理由がない（`PoiFlavorFacts` の「座標は渡さない」と同じ方針で、
 * こちらは地点名すら渡さないぶんさらに狭い）。
 */
data class SpeciesDescriptionFacts(
    val name: String,
    val category: CodexCategory,
) {
    companion object {
        /** カタログの種から、渡してよい事実だけを抜き出す。 */
        fun of(species: Species): SpeciesDescriptionFacts = SpeciesDescriptionFacts(
            name = species.name,
            category = species.category,
        )
    }
}

/** 図鑑の記述文のプロンプトを組み立てる（純関数）。 */
object SpeciesDescriptionPromptBuilder {

    /**
     * 役割と禁止事項。**design.md §4.4「フィクション扱い」と §7「事実は書かせない」の実装**。
     *
     * 出力をJSON固定にしているのは、モデルが前置き（「はい、承知しました」）を付けても
     * [SpeciesDescriptionResponseParser] が本文だけを取り出せるようにするため。
     */
    val systemPrompt: String = """
        あなたは散歩ゲームの中にある**架空の図鑑**の記述を書く担当です。
        プレイヤーが同じ場所に何度も通って、ようやく出会えた相手の1ページです。

        守ること:
        - 日本語で2〜3文、合わせて120文字程度まで。
        - 静かなトーンで、説明しすぎない。感嘆符は使わない。
        - 書いてよいのは、その生き物や植物の佇まい・気配・見え方・そこに居るときの空気だけ。
        - 図鑑らしい事実は書かない。分類・学名・体長・生態・分布・食性・季節の断定・
          保護状況など、調べれば真偽が決まることは一切書かない。
          悪い例: 「体長17cmほどのカワセミ科の鳥」 よい例: 「水の上を、まっすぐな線が横切る」
        - 実在の場所の話はしない。地名・施設・歴史・由来には触れない。
        - 実際にそこに生息していると断定しない。「必ず見られる」とも書かない。
        - 案内・おすすめ・注意喚起・プレイヤーへの指示はしない。
        - 出力はJSONのみ。{"description": "本文"} の形だけを返し、前後に説明やコードブロックを付けない。
    """.trimIndent()

    /** 事実だけを並べた依頼文。並び順を固定するのは、同じ種で毎回同じ指紋にするため。 */
    fun userPrompt(facts: SpeciesDescriptionFacts): String = buildString {
        append("名前: ").append(facts.name)
        append('\n')
        append("見つかる場所: ").append(facts.category.label())
    }

    /**
     * 1件ぶんの生成依頼。
     *
     * @param maxTokens [LlmGenerationConfig.speciesDescriptionMaxTokens] を渡す。
     */
    fun request(facts: SpeciesDescriptionFacts, maxTokens: Int): LlmRequest = LlmRequest(
        systemPrompt = systemPrompt,
        userPrompt = userPrompt(facts),
        maxTokens = maxTokens,
    )
}

/**
 * `{"description": "..."}` から本文を取り出す（純関数）。
 *
 * エスケープの解き方は [LlmJsonText] に置いてある。ここに残っているのはキー名だけ。
 *
 * @return 取り出せた本文（前後の空白は落とす）。見つからない・空・途中で切れているなら `null`
 *  ＝呼び出し側は定型文（[SpeciesDescriptionFallback]）で凌ぐ。
 */
object SpeciesDescriptionResponseParser {

    private const val KEY = "description"

    fun parse(raw: String): String? = LlmJsonText.value(raw, KEY)
}

/**
 * 生成できていない種で使う定型文（design.md §7「失敗・圏外なら定型文＋事前生成分で成立する」）。
 *
 * 棚ごとに1つだけ持つ。凝った文章にしないのは `PoiFlavorFallback` と同じ理由。
 * **事実は一切含まない**（含めた瞬間にLLMに禁じているルールを自分で破ることになる）。
 *
 * 図鑑は種が有限（`SpeciesCatalog`）なので、事前バッチが一度でも回れば全種埋まる。
 * この定型文が出るのは、セットアップ直後にいきなり1種目を見つけたときくらい。
 */
object SpeciesDescriptionFallback {

    fun text(category: CodexCategory): String = when (category) {
        CodexCategory.PARK -> "木々のあいだに、いつのまにか居る。通うほどに、見つけやすくなる。"
        CodexCategory.WATER -> "水の近くで、しばらく動かずにいる。目が合う前に、もう離れている。"
        CodexCategory.RAILWAY -> "線路のそばの高いところが好きらしい。電車が過ぎても、動じない。"
        CodexCategory.FARMLAND -> "畑のあいだの、開けた空の下にいる。近づくと少しだけ間をとる。"
        CodexCategory.TREE -> "見上げたときにだけ、そこに居たことが分かる。声のほうが先に届く。"
        CodexCategory.SHRINE -> "静かな木立の奥にいる。気配だけが、こちらに向いている。"
        CodexCategory.URBAN -> "人の通りの隅に、当たり前のようにいる。こちらのことは知っている。"
    }
}

/** プロンプトに書く棚の呼び名。enumの名前をそのまま出すより、モデルが素直に受け取れる。 */
private fun CodexCategory.label(): String = when (this) {
    CodexCategory.PARK -> "公園・緑地"
    CodexCategory.WATER -> "水辺（川・池・用水路など）"
    CodexCategory.RAILWAY -> "鉄道に関わる場所（線路・駅・踏切など）"
    CodexCategory.FARMLAND -> "農地"
    CodexCategory.TREE -> "街路樹などの樹木"
    CodexCategory.SHRINE -> "寺社"
    CodexCategory.URBAN -> "街なか"
}

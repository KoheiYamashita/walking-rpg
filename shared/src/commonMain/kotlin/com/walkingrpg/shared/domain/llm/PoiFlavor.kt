package com.walkingrpg.shared.domain.llm

import com.walkingrpg.shared.domain.osm.Poi
import com.walkingrpg.shared.domain.osm.PoiKind

/**
 * 地点フレーバー（パートナーの一言として出す1〜2文）のプロンプトと後始末。
 *
 * design.md §7 の「任せる／任せない」をコードに落とした場所：
 * **LLMに任せるのは情景と感覚の文章化だけ**で、実在の場所の事実（歴史・由来）は
 * 書かせない。事実側の材料（種別・名前）はこちらから渡し、それ以外を作らせない。
 *
 * 純Kotlin。JSONのパースまで手で書いてあるのは、ドメイン層に
 * kotlinx.serialization を持ち込まないため（`shared/build.gradle.kts` の
 * commonMain の注記「domain は外部依存なしの純Kotlin」）。
 */

/**
 * プロンプトに載せる事実。**ここに無いものはLLMに渡らない。**
 *
 * ## 座標は渡さない
 * 天候APIに座標を丸めて送っている（`WeatherQueryPlanner`）のと同じ理由で、
 * 外部に出す位置情報は最小限にする。フレーバーは「その種類の場所で人が感じること」なので、
 * 緯度経度が無くても書ける。自宅の情報（`HomeAnchor`）はどの経路でも渡らない。
 *
 * @param name OSMの `name` タグ。同じ種別の地点が全部同じ文章になるのを避けるための
 *  手がかりとして渡す（OSMの公開情報であり、無名の地物では `null`）。
 *  名前から由来を推測させないことはシステムプロンプトで縛る。
 */
data class PoiFlavorFacts(
    val kind: PoiKind,
    val name: String?,
) {
    companion object {
        /** マスタのPOIから、渡してよい事実だけを抜き出す。 */
        fun of(poi: Poi): PoiFlavorFacts = PoiFlavorFacts(
            kind = poi.kind,
            name = poi.tags["name"]?.trim()?.takeIf { it.isNotEmpty() },
        )
    }
}

/** 地点フレーバーのプロンプトを組み立てる（純関数）。 */
object PoiFlavorPromptBuilder {

    /**
     * 役割と禁止事項。**design.md §7「実在の場所の事実は書かせない」の実装**。
     *
     * 出力をJSON固定にしているのは、モデルが前置き（「はい、承知しました」）を付けても
     * [PoiFlavorResponseParser] が本文だけを取り出せるようにするため。
     */
    val systemPrompt: String = """
        あなたは散歩ゲームの相棒として、場所の「情景と感覚」だけを短く書き出す担当です。

        守ること:
        - 日本語で1〜2文、合わせて60文字程度まで。
        - 書いてよいのは、その種類の場所で人が感じうる情景・音・匂い・光・空気などの感覚だけ。
        - 実在の場所の事実は書かない。歴史・由来・創建・年代・人物・伝承・地名の意味・
          施設の規模や設備など、調べれば真偽が決まることは一切書かない。
          悪い例: 「江戸時代に創建された神社」 よい例: 「登り切ると空が広い」
        - 名前はその場所を指すための手がかりにすぎない。名前から意味や由来を推測して書かない。
        - 案内・おすすめ・注意喚起はしない。
        - 出力はJSONのみ。{"flavor": "本文"} の形だけを返し、前後に説明やコードブロックを付けない。
    """.trimIndent()

    /** 事実だけを並べた依頼文。並び順を固定するのは、同じ地点で毎回同じ指紋にするため。 */
    fun userPrompt(facts: PoiFlavorFacts): String = buildString {
        append("場所の種類: ").append(facts.kind.label())
        append('\n')
        append("場所の名前: ").append(facts.name ?: "（無名）")
    }

    /**
     * 1件ぶんの生成依頼。
     *
     * @param maxTokens [LlmGenerationConfig.poiFlavorMaxTokens] を渡す。
     */
    fun request(facts: PoiFlavorFacts, maxTokens: Int): LlmRequest = LlmRequest(
        systemPrompt = systemPrompt,
        userPrompt = userPrompt(facts),
        maxTokens = maxTokens,
    )
}

/**
 * `{"flavor": "..."}` から本文を取り出す（純関数）。
 *
 * ## 手書きにした理由
 * ドメイン層に kotlinx.serialization を持ち込まないため（このファイルのKDoc）。
 * 加えて、モデルはJSONの前後に前置きやコードブロックを付けてくることがあるので、
 * 厳格なパーサに通すより「`"flavor"` の値だけを拾う」ほうがそのまま強い。
 *
 * @return 取り出せた本文（前後の空白は落とす）。見つからない・空・途中で切れているなら `null`
 *  ＝呼び出し側は定型文（[PoiFlavorFallback]）で凌ぐ。
 */
object PoiFlavorResponseParser {

    private const val KEY = "\"flavor\""

    fun parse(raw: String): String? {
        val keyIndex = raw.indexOf(KEY)
        if (keyIndex < 0) return null
        val colonIndex = raw.indexOf(':', startIndex = keyIndex + KEY.length)
        if (colonIndex < 0) return null
        val openQuote = raw.indexOf('"', startIndex = colonIndex + 1)
        if (openQuote < 0) return null
        return raw.readJsonString(openQuote + 1)?.trim()?.takeIf { it.isNotEmpty() }
    }

    /**
     * [from] からJSON文字列の閉じ引用符までを読む（エスケープを解く）。
     *
     * 閉じ引用符に辿り着けない＝出力上限で途中で切れた応答なので `null`
     * （半端な文章をキャッシュに焼き付けたくない）。
     */
    private fun String.readJsonString(from: Int): String? {
        val text = StringBuilder()
        var index = from
        while (index < length) {
            when (val char = this[index]) {
                '"' -> return text.toString()
                '\\' -> {
                    val escaped = getOrNull(index + 1) ?: return null
                    when (escaped) {
                        '"' -> text.append('"')
                        '\\' -> text.append('\\')
                        '/' -> text.append('/')
                        'n' -> text.append('\n')
                        'r' -> text.append('\r')
                        't' -> text.append('\t')
                        'b' -> text.append('\b')
                        'f' -> text.append('\u000C')
                        'u' -> {
                            val code = substring(
                                startIndex = index + 2,
                                endIndex = minOf(index + 6, length),
                            ).takeIf { it.length == 4 }?.toIntOrNull(radix = 16) ?: return null
                            text.append(code.toChar())
                            index += 4
                        }

                        else -> return null // 知らないエスケープ＝JSONとして壊れている
                    }
                    index += 2
                }

                else -> {
                    text.append(char)
                    index++
                }
            }
        }
        return null
    }
}

/**
 * 生成できていない地点で使う定型文（design.md §7「失敗・圏外なら定型文＋事前生成分で成立する」）。
 *
 * 種別ごとに1つだけ持つ。凝った文章にしないのは、これが**出てはいけない文章ではない**一方で
 * 「生成済みの文章と区別がつかないほど良い」必要も無いから。事実は一切含まない
 * （含めた瞬間にLLMに禁じているルールを自分で破ることになる）。
 */
object PoiFlavorFallback {

    fun text(kind: PoiKind): String = when (kind) {
        PoiKind.PARK -> "木々のあいだを風が通っていく。"
        PoiKind.WATER -> "水の音が近い。"
        PoiKind.RAILWAY -> "遠くで電車の音がした。"
        PoiKind.FARMLAND -> "空が広く、土の匂いがする。"
        PoiKind.SHRINE -> "空気がすこし静かになる。"
        PoiKind.PUBLIC -> "人の出入りの気配がある。"
        PoiKind.SHOP -> "通りのにぎわいが少し届く。"
        PoiKind.TREE -> "見上げると枝葉が広がっている。"
        PoiKind.LANDMARK -> "目印のように、そこに立っている。"
    }
}

/** プロンプトに書く種別の呼び名。enumの名前をそのまま出すより、モデルが素直に受け取れる。 */
private fun PoiKind.label(): String = when (this) {
    PoiKind.PARK -> "公園・緑地"
    PoiKind.WATER -> "水辺（川・池・用水路など）"
    PoiKind.RAILWAY -> "鉄道に関わる場所（線路・駅・踏切など）"
    PoiKind.FARMLAND -> "農地"
    PoiKind.SHRINE -> "寺社"
    PoiKind.PUBLIC -> "公共施設"
    PoiKind.SHOP -> "商店"
    PoiKind.TREE -> "樹木"
    PoiKind.LANDMARK -> "目印になる地物"
}

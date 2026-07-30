package com.walkingrpg.shared.domain.codex

/**
 * 図鑑に載る種の一覧（design.md §4.4）。**手書きの定数リスト**。
 *
 * ## なぜ手書きなのか
 * design.md §7「任せない（手で決める骨）」。何が何回で出るかは体験の骨格そのもので、
 * LLMに作らせると実行ごとに違うものが出る＝**同じ passage 列から同じ図鑑状態が出なくなる**
 * （architecture.md §7 の冪等性テストが成立しない）。LLMに任せるのは記述文だけ
 * （[com.walkingrpg.shared.domain.llm.SpeciesDescriptionPromptBuilder]）。
 *
 * ## 選定の規則（design.md §4.4「実在度：フィクション扱い、選定は実在の生態準拠」）
 * - **あり得ない生き物は出さない**（東京の川にカワセミはあり得る。ペンギンは出さない）
 * - その場所に実際にいるとは主張しない。棚の種類として自然かどうかだけを見る
 * - 名前は日本語。特定の場所と結びつく固有名は出さない
 *
 * ## 閾値の設計基準
 * 3〜10回に散らしてある。根拠は2つ：
 *
 * - **上は10回まで**（design.md §4.4「同じ川に10回通って初めて現れる」がそのまま最遠）。
 *   道の到達点（`GrowthConfig.creaturePassCount` = 50回）より必ず手前に置く
 *   ＝道の「生き物が住みつく」が図鑑より先に来ない（GrowthConfig のKDoc）。
 *   毎日その前を通れば10日、週3回なら1ヶ月弱＝design.md §4.4「数ヶ月で埋まる」に収まる
 * - **下は3回**。1回では「歩いたら出た」になって通う意味が消え、2回では
 *   予兆（[CodexConfig.foreshadowLeadVisits] = 2回手前）を経由できない
 *
 * 棚ごとに2〜3種で、近い閾値と遠い閾値を必ず混ぜる：手近な1種がすぐ埋まって
 * 「この棚は動いた」が見え、遠い1種が残って通う理由になる。
 *
 * 実プレイで合わなければ**ここだけ**触って [RecomputeCodexProgressUseCase] を流す
 * （`codex_progress` は捨てて作り直せる導出キャッシュ）。テストは閾値そのものではなく
 * 「散らばっていること」「道の到達点より手前であること」を守る（`SpeciesCatalogTest`）。
 */
object SpeciesCatalog {

    /**
     * 全ての種。**表示順はこの並び**（棚ごとに、閾値の近い順）。
     *
     * `species_id` は `codex_progress` と `llm_cache` の論理キーに入るので、
     * **一度決めたら変えない**（[Species.id] のKDoc）。
     */
    val ALL: List<Species> = listOf(
        // --- 水辺（4本柱） ---
        Species(
            id = "water_skimmer",
            name = "シオカラトンボ",
            category = CodexCategory.WATER,
            requiredVisitCount = 3,
            foreshadowText = "水面の上を、何かが低く行き来している。",
        ),
        Species(
            id = "water_spotbill_duck",
            name = "カルガモ",
            category = CodexCategory.WATER,
            requiredVisitCount = 6,
            foreshadowText = "岸の泥に、水かきの跡が並んでいる。",
        ),
        Species(
            id = "water_kingfisher",
            name = "カワセミ",
            category = CodexCategory.WATER,
            requiredVisitCount = 10,
            foreshadowText = "水辺に、青い羽根が一枚落ちている。",
        ),

        // --- 公園（4本柱） ---
        Species(
            id = "park_turtle_dove",
            name = "キジバト",
            category = CodexCategory.PARK,
            requiredVisitCount = 3,
            foreshadowText = "草のあいだから、地面をつつく音がする。",
        ),
        Species(
            id = "park_seasonal_flower",
            name = "季節の花",
            category = CodexCategory.PARK,
            requiredVisitCount = 5,
            foreshadowText = "花壇の土が、少し盛り上がっている。",
        ),
        Species(
            id = "park_swallowtail",
            name = "アオスジアゲハ",
            category = CodexCategory.PARK,
            requiredVisitCount = 8,
            foreshadowText = "木の花のまわりを、影が横切った。",
        ),

        // --- 鉄道（4本柱） ---
        Species(
            id = "railway_swallow",
            name = "ツバメ",
            category = CodexCategory.RAILWAY,
            requiredVisitCount = 4,
            foreshadowText = "軒下に、乾いた泥がひとつまみ落ちている。",
        ),
        Species(
            id = "railway_wagtail",
            name = "ハクセキレイ",
            category = CodexCategory.RAILWAY,
            requiredVisitCount = 7,
            foreshadowText = "線路際で、尾を上下に振る影を見た。",
        ),

        // --- 農地（4本柱） ---
        Species(
            id = "farmland_cabbage_white",
            name = "モンシロチョウ",
            category = CodexCategory.FARMLAND,
            requiredVisitCount = 3,
            foreshadowText = "畦の上を、白い点がひらひらしていた。",
        ),
        Species(
            id = "farmland_sparrow",
            name = "スズメ",
            category = CodexCategory.FARMLAND,
            requiredVisitCount = 5,
            foreshadowText = "穂が、風もないのに揺れた。",
        ),
        Species(
            id = "farmland_mantis",
            name = "カマキリ",
            category = CodexCategory.FARMLAND,
            requiredVisitCount = 9,
            foreshadowText = "葉のふちに、細長い影が伸びている。",
        ),

        // --- 樹木 ---
        Species(
            id = "tree_cicada",
            name = "セミ",
            category = CodexCategory.TREE,
            requiredVisitCount = 4,
            foreshadowText = "幹に、小さな抜け殻がしがみついている。",
        ),
        Species(
            id = "tree_white_eye",
            name = "メジロ",
            category = CodexCategory.TREE,
            requiredVisitCount = 8,
            foreshadowText = "枝の奥で、鈴のような声がした。",
        ),

        // --- 寺社 ---
        Species(
            id = "shrine_tree_cricket",
            name = "アオマツムシ",
            category = CodexCategory.SHRINE,
            requiredVisitCount = 6,
            foreshadowText = "木の上から、途切れない音がしている。",
        ),
        Species(
            id = "shrine_owl",
            name = "フクロウ",
            category = CodexCategory.SHRINE,
            requiredVisitCount = 10,
            foreshadowText = "石段に、灰色の羽根が一枚。",
        ),

        // --- 市街 ---
        Species(
            id = "urban_cat",
            name = "ネコ",
            category = CodexCategory.URBAN,
            requiredVisitCount = 3,
            foreshadowText = "塀の上に、乾いた足跡が残っている。",
        ),
        Species(
            id = "urban_starling",
            name = "ムクドリ",
            category = CodexCategory.URBAN,
            requiredVisitCount = 7,
            foreshadowText = "街路樹の下に、羽根と種が散っている。",
        ),
    )

    init {
        // IDは進捗（codex_progress）と生成済みの記述文（llm_cache）の主キーになるので、
        // かぶっていると片方が黙って上書きされる。起動時に落とす方が安全。
        require(ALL.map { it.id }.distinct().size == ALL.size) { "species id が重複している" }
    }

    /** 棚ごとの種（表示順は [ALL] の並びのまま）。空の棚も [emptyList] として引ける。 */
    val byCategory: Map<CodexCategory, List<Species>> =
        CodexCategory.entries.associateWith { category -> ALL.filter { it.category == category } }

    /** IDから引く。知らないIDは `null`（DBに古い行が残っていても落ちない）。 */
    fun species(id: String): Species? = ALL.firstOrNull { it.id == id }
}

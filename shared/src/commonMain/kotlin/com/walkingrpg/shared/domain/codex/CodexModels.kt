package com.walkingrpg.shared.domain.codex

import com.walkingrpg.shared.domain.osm.PoiKind

/**
 * 図鑑のドメインモデル（design.md §4.4・§8「図鑑カテゴリ体系」、architecture.md §4「導出」）。
 *
 * 純Kotlin。SQLDelightの行型もプラットフォームAPIもここには現れない。
 */

/**
 * 図鑑のカテゴリ（design.md §8「公園 / 水辺 / 鉄道 / 農地 / 樹木 / 市街 …」）。
 *
 * design.md §9 のOSM監査で「図鑑素材を微細アメニティに依存させない。公園・水辺・鉄道・農地の
 * **4本柱**で組む」と結論が出ているので、その4つを先頭に置き、監査で素材が確認できた
 * 樹木（natural=tree 25本）・寺社・市街を足した7つにしてある。
 *
 * `PoiKind`（取り込みの分類）と1対1ではない：POIの種別は「地物を分類する軸」で、
 * こちらは「図鑑の棚を分ける軸」。商店・公共施設・ランドマークはどれも
 * 「街の中の地物」として同じ棚（[URBAN]）に入る＝棚を細かく割っても
 * 手書きの種カタログ（[SpeciesCatalog]）が薄くなるだけで、集める体験は良くならない。
 *
 * 夜（design.md §8「… / 夜」）は季節・時間帯・天候の**変奏**の軸であって棚ではないので、
 * ここには入れない（変奏はMVP後回し。design.md §10）。
 */
enum class CodexCategory {
    /** 公園・緑地。design.md §9 の監査で15箇所＝最重要素材。 */
    PARK,

    /** 水辺（川・池・用水路）。カワセミ枠のある棚。 */
    WATER,

    /** 鉄道に関わる場所（線路・駅・踏切・跨線橋）。 */
    RAILWAY,

    /** 農地。都市農地は季節変奏と相性がよい独自カテゴリ（design.md §9）。 */
    FARMLAND,

    /** 街路樹などの樹木。点素材。 */
    TREE,

    /** 寺社。500m圏では手薄で、行動圏拡張の報酬として働く（design.md §9）。 */
    SHRINE,

    /** 市街（商店・公共施設・目印になる地物）。 */
    URBAN,
}

/**
 * POIの種別を図鑑の棚に対応づける。
 *
 * `when` を網羅で書くのは、[PoiKind] が増えたときに
 * 「どの棚に入れるか」を決め忘れないようにするため（コンパイルエラーで気付く）。
 */
fun PoiKind.toCodexCategory(): CodexCategory = when (this) {
    PoiKind.PARK -> CodexCategory.PARK
    PoiKind.WATER -> CodexCategory.WATER
    PoiKind.RAILWAY -> CodexCategory.RAILWAY
    PoiKind.FARMLAND -> CodexCategory.FARMLAND
    PoiKind.TREE -> CodexCategory.TREE
    PoiKind.SHRINE -> CodexCategory.SHRINE
    PoiKind.SHOP, PoiKind.PUBLIC, PoiKind.LANDMARK -> CodexCategory.URBAN
}

/**
 * 図鑑に載る1種（design.md §4.4）。
 *
 * **手書きの骨**（design.md §7「任せない（手で決める骨）」）。LLMに作らせるのは
 * 記述文（[com.walkingrpg.shared.domain.llm.SpeciesDescriptionPromptBuilder]）だけで、
 * 何が何回で出るかはコードの中の定数リスト（[SpeciesCatalog]）で決める。
 *
 * @param id 安定した識別子。`codex_progress.species_id` と `llm_cache` の論理キーに入るので、
 *  **一度決めたら変えない**（変えると進捗と生成済みの記述文が孤児になる）。
 * @param name 表示名（日本語）。
 * @param category どの棚か。訪問回数はこの棚の中のPOI単位で数える（[CodexProgressCalculator]）。
 * @param requiredVisitCount 出現に必要な訪問回数。design.md §4.4「同じ川に10回通って初めて
 *  現れる」がこの数字で、**確率は使わない**（満たせば必ず出る）。
 * @param foreshadowText 閾値の手前で出す予兆（design.md §4.4「川辺に青い羽根が落ちている」）。
 *  残り回数を数字で見せない代わりの仕掛けなので、**種名を書かない**：
 *  「カワセミの羽根」と書いたら数字を見せているのと同じになる。
 */
data class Species(
    val id: String,
    val name: String,
    val category: CodexCategory,
    val requiredVisitCount: Int,
    val foreshadowText: String,
) {
    init {
        require(id.isNotBlank()) { "species id は空にしない" }
        require(requiredVisitCount >= 1) { "requiredVisitCount は1以上" }
    }
}

/**
 * 予兆の段（`codex_progress.foreshadow_stage`）。
 *
 * design.md §4.4「数字で見せると作業になり、完全に隠すと出現が唐突になる。予兆はその中間」。
 * 今は「まだ何も無い」「もう近い」の2段だけ。段を増やすときはここに足して
 * [level] を振れば、DBは INTEGER のままでよい（導出キャッシュなので再計算で埋まる）。
 *
 * @param level DBに入る値。**一度決めたら変えない**（[fromLevel] が読めなくなる）。
 */
enum class ForeshadowStage(val level: Int) {
    /** 何も出さない。閾値がまだ遠い、あるいは既に発見済み。 */
    NONE(0),

    /** 閾値が目の前（[CodexConfig.foreshadowLeadVisits] 回以内）。予兆の文章を出す。 */
    NEAR(1),
    ;

    companion object {
        /**
         * DBの値から戻す。知らない値は [NONE]（段を減らしたあとの古い行）。
         *
         * `codex_progress` は捨てて作り直せる導出キャッシュなので、
         * 読めない行に合わせて例外を捌くより、次の再計算で入れ替わるのを待つほうがよい。
         */
        fun fromLevel(level: Long): ForeshadowStage =
            entries.firstOrNull { it.level.toLong() == level } ?: NONE
    }
}

/**
 * 1種ぶんの進捗（architecture.md §4 `codex_progress(species_id, visit_count, foreshadow_stage,
 * discovered_at?)`）。
 *
 * これは**導出キャッシュ**であって真実の源ではない。真実は `passage`（さらに遡れば
 * `location_sample`）だけが持っていて、この値はいつ捨てても
 * [RecomputeCodexProgressUseCase] で同じものが再生する
 * （architecture.md §4「導出テーブルはすべて passage から再計算できること」）。
 *
 * @param visitCount その種の棚（[Species.category]）への訪問回数。数え方は
 *  [CodexProgressCalculator] のKDoc「訪問回数の定義」。1以上（0回の種は行を作らない＝
 *  未発見の種は [SpeciesCatalog] 側にしか現れない。`way_growth` と同じ考え方）。
 * @param discoveredAtMs 発見した時刻（epoch millis）。まだ出ていなければ `null`。
 *  **端末時計は読まない**：閾値に到達したセッションの `passage.ts` から引く
 *  （[CodexProgressCalculator] のKDoc「発見時刻」）。読んだ瞬間にこの導出は冪等でなくなる。
 */
data class CodexProgress(
    val speciesId: String,
    val visitCount: Int,
    val foreshadowStage: ForeshadowStage,
    val discoveredAtMs: Long? = null,
) {
    init {
        require(visitCount >= 1) { "visitCount は1以上（0回の種は行を作らない）" }
        // 発見済みの種に予兆は出さない（出したら「もう近い」と「もう出た」が同時に立つ）
        require(discoveredAtMs == null || foreshadowStage == ForeshadowStage.NONE) {
            "発見済みの種の foreshadowStage は NONE"
        }
    }

    val isDiscovered: Boolean get() = discoveredAtMs != null
}

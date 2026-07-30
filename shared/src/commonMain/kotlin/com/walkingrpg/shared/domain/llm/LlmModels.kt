package com.walkingrpg.shared.domain.llm

/**
 * LLM生成基盤のドメインモデル（issue #14・design.md §7・architecture.md §5「LLM運用」）。
 *
 * 純Kotlin。Anthropic / OpenAI のリクエスト形式もKtorもここには現れない
 * （フォーマットの違いはデータ層の2実装に閉じる）。
 */

/**
 * 生成タスクの種別。`llm_cache.kind` に入る値そのもの。
 *
 * キャッシュの区画を分けるためのもので、「同じ地点の文章でも用途が違えば別の行」になる。
 * **値の名前は変えないこと**：DBに文字列で入っているので、
 * 改名すると既存キャッシュが読めなくなる（読めなくなっても作り直せるだけで壊れはしないが、
 * 一斉に再生成＝課金が走る）。
 */
enum class LlmTaskKind {
    /** 地点（POI）ごとの情景フレーバー1〜2文。Wi-Fi時の事前バッチで作る（design.md §7）。 */
    POI_FLAVOR,

    /**
     * 図鑑の種ごとの記述文2〜3文（issue #13・design.md §4.4）。
     * 種は手書きのカタログで有限（`SpeciesCatalog`）なので、事前バッチで全部作り切れる。
     */
    SPECIES_DESCRIPTION,

    /**
     * 振り返りのパートナーの一言2〜3文（issue #15・design.md §4.5・§5「帰宅後に語る」）。
     *
     * 材料は散歩1回ぶんの確定データ（`WalkReview`）なので、地点・種と違って
     * **事前には作れない**（歩き終わるまで内容が存在しない）。帰宅直後に1件だけ投げて、
     * 生成できたら画面に差し込む（architecture.md §5「数値は即時、文章は遅延OK」）。
     *
     * 記憶の参照（1ヶ月前のログ）と単調化対策（`utterance_log` による切り口の再使用禁止。
     * design.md §5）は #16 の担当で、ここにはまだ無い。
     */
    WALK_REVIEW_REMARK,
}

/**
 * LLMに投げる1回ぶんの生成依頼。
 *
 * **接続設定（フォーマット・URL・モデル・キー）はここに入れない**：それは
 * `LlmConnectionSettings` の役目で、依頼のたびに読み直す
 * （[LlmClient.generate] のKDoc「設定を引数で渡す理由」）。
 *
 * プロンプトは呼び出し側（ドメインのプロンプトビルダー）が組み立て済みのものを渡す。
 * データ層はこれを各フォーマットのJSONに詰め替えるだけで、文言には関与しない。
 *
 * @param maxTokens 出力の上限。地点フレーバーは1〜2文なので300程度で足りる
 *  （design.md §7「1地点あたり約300トークン」）。上限を切るのは課金の蓋であると同時に、
 *  長文を返してくるモデルの相手をしないため。
 * @param allowRetry 通信失敗・5xx でHTTP層に再試行させてよいか。
 *  **疎通確認（セットアップ画面）だけ `false`**：ベースURLの綴り間違いで
 *  名前解決に失敗しているときに数秒粘られると、ユーザーは「入力を直す」までの
 *  待ち時間が伸びるだけで得がない。事前バッチは家の中で走るので粘ってよい。
 */
data class LlmRequest(
    val systemPrompt: String,
    val userPrompt: String,
    val maxTokens: Int,
    val allowRetry: Boolean = true,
) {
    init {
        require(maxTokens > 0) { "maxTokens は正の値" }
    }
}

/**
 * 生成結果。
 *
 * 中身は**モデルが返した本文そのまま**（JSON文字列で返させるタスクなら、そのJSON文字列）。
 * 意味のあるフィールドへの解釈はタスクごとのパーサ（例 [PoiFlavorResponseParser]）が行う。
 * データ層はフォーマット差（Anthropicの `content[]` / OpenAIの `choices[]`）を吸収して
 * ここに揃えるところまでを受け持つ。
 */
data class LlmResponse(
    val text: String,
)

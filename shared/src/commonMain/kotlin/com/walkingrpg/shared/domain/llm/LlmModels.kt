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
 * 今は地点フレーバー1種だけだが、#15（図鑑の記述文）・#16（振り返り・パートナーの一言）で
 * 増える前提の enum にしてある。**値の名前は変えないこと**：DBに文字列で入っているので、
 * 改名すると既存キャッシュが読めなくなる（読めなくなっても作り直せるだけで壊れはしないが、
 * 一斉に再生成＝課金が走る）。
 */
enum class LlmTaskKind {
    /** 地点（POI）ごとの情景フレーバー1〜2文。Wi-Fi時の事前バッチで作る（design.md §7）。 */
    POI_FLAVOR,
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

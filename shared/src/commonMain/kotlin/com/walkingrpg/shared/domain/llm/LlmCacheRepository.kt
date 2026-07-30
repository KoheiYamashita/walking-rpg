package com.walkingrpg.shared.domain.llm

/**
 * 生成済みテキストのキャッシュ（`llm_cache`）の永続化境界（architecture.md §4「LLM」）。
 *
 * ## キーの設計：`kind` ＋ 論理キー ＋ prompt_hash
 *
 * ```
 * cache_key   = "POI_FLAVOR:poi:node/123"   ← 何の文章か（引くためのキー・主キー）
 * prompt_hash = "1f3c…"                     ← どのプロンプトで作ったか（作り直しの判定）
 * ```
 *
 * 引くのは [cacheKey] だけ。[LlmCacheEntry.promptHash] が今のプロンプトと違えば
 * 「無い」ものとして扱い、生成して**同じ行を置き換える**（1論理キー1行）。
 * こうしておくと、プロンプトを直したときは自然に作り直され、直していないときは
 * 二度と課金されない。ハッシュまで主キーに含めると、プロンプトを直すたびに
 * 古い行がゴミとして残り続ける。
 *
 * ## POIのIDへの外部キーは張らない
 * 対象圏を取り直すとPOIマスタは**全削除して作り直す**（`OsmMasterRepository.save`）。
 * FKを張ると、取り直しのたびに生成済みの文章が連鎖削除される＝課金し直しになる。
 * 論理キーに `poi/<osm id>` を埋めておけば、マスタが作り直されても
 * 同じ地物には同じキーで再びヒットする（OSMのIDは地物の同一性を表す）。
 * マスタから消えた地物のぶんは孤児として残るが、テキスト数百件ぶんなので放置してよい。
 *
 * ## 捨てて作り直せる
 * このテーブルは導出キャッシュ（architecture.md §4「導出」の考え方と同じ）。
 * 消えたら生成し直せばよく、読み側は生成されていない地点を定型文で凌ぐ
 * （[PoiFlavorFallback]・design.md §7）。真実の源ではない。
 */
interface LlmCacheRepository {

    /** 1件引く。行が無ければ `null`（プロンプト一致の判定は呼び出し側＝[LlmCacheEntry.matches]）。 */
    suspend fun entry(cacheKey: String): LlmCacheEntry?

    /**
     * 同じ [LlmTaskKind] の行を全部引く（キー→行）。
     *
     * 事前バッチは数百地点を一度に見るので、1件ずつ [entry] を呼ぶと
     * 「無いことの確認」だけで数百クエリになる。まとめて1回読んでメモリで突き合わせる。
     * 1件あたり数百バイトのテキストなので、対象圏ぶん（数百件）なら問題にならない。
     */
    suspend fun entries(kind: LlmTaskKind): Map<String, LlmCacheEntry>

    /** 保存する。同じ [LlmCacheEntry.cacheKey] の行があれば置き換える（1論理キー1行）。 */
    suspend fun save(entry: LlmCacheEntry)
}

/**
 * キャッシュ1行。
 *
 * @param cacheKey 何の文章か（[llmCacheKey] が組み立てる）。
 * @param promptHash このテキストを作ったときのプロンプトの指紋（[PromptHash]）。
 * @param createdAtMs この行を書いた時刻（epoch millis）。
 */
data class LlmCacheEntry(
    val cacheKey: String,
    val kind: LlmTaskKind,
    val promptHash: String,
    val text: String,
    val createdAtMs: Long,
) {
    /** 今から投げようとしているプロンプトで作られた行か（違えば作り直す）。 */
    fun matches(promptHash: String): Boolean = this.promptHash == promptHash
}

/**
 * キャッシュキーを組み立てる。
 *
 * [kind] を頭に付けるのは、論理キーが偶然かぶっても種別が違えば別の行にするため
 * （同じ地点について「フレーバー」と「図鑑の記述文」を別々に持てる）。
 *
 * @param logicalKey 種別の中で一意な識別子（例 `poi:node/123`）。
 */
fun llmCacheKey(kind: LlmTaskKind, logicalKey: String): String = "${kind.name}:$logicalKey"

/** 地点フレーバーの論理キー。POIのIDはOSMの種別込み（`node/123`）なので、そのまま使える。 */
fun poiFlavorLogicalKey(poiId: String): String = "poi:$poiId"

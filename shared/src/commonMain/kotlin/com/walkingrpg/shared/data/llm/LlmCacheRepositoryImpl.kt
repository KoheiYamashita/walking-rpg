package com.walkingrpg.shared.data.llm

import com.walkingrpg.shared.data.db.Llm_cache
import com.walkingrpg.shared.data.db.WalkingRpgDatabase
import com.walkingrpg.shared.domain.llm.LlmCacheEntry
import com.walkingrpg.shared.domain.llm.LlmCacheRepository
import com.walkingrpg.shared.domain.llm.LlmTaskKind
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [LlmCacheRepository] のSQLDelight実装。
 *
 * ドメインモデル⇄DBスキーマの変換はこのクラスに閉じる（architecture.md §2）。
 * 保存は `cache_key` を主キーにした `INSERT OR REPLACE`（LlmCache.sq）＝
 * 同じ論理キーを何度生成しても1行。
 */
internal class LlmCacheRepositoryImpl(
    private val database: WalkingRpgDatabase,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : LlmCacheRepository {

    private val cache get() = database.llmCacheQueries

    override suspend fun entry(cacheKey: String): LlmCacheEntry? = withContext(dispatcher) {
        cache.selectLlmCache(cacheKey).executeAsOneOrNull()?.toEntry()
    }

    override suspend fun entries(kind: LlmTaskKind): Map<String, LlmCacheEntry> =
        withContext(dispatcher) {
            cache.selectLlmCacheByKind(kind.name)
                .executeAsList()
                .mapNotNull { row -> row.toEntry() }
                .associateBy { it.cacheKey }
        }

    override suspend fun save(entry: LlmCacheEntry): Unit = withContext(dispatcher) {
        cache.upsertLlmCache(
            cache_key = entry.cacheKey,
            kind = entry.kind.name,
            prompt_hash = entry.promptHash,
            text = entry.text,
            created_at = entry.createdAtMs,
        )
    }
}

/**
 * DBの行 → ドメインの [LlmCacheEntry]。
 *
 * `kind` が未知の値（種別を改名した後の古い行）なら、その行は読まなかったことにする
 * ＝呼び出し側からは「未生成」に見え、生成し直して置き換えられる。
 * キャッシュは捨てて作り直せるので、落ちるより静かに作り直すほうがよい。
 */
private fun Llm_cache.toEntry(): LlmCacheEntry? {
    val taskKind = LlmTaskKind.entries.firstOrNull { it.name == kind } ?: return null
    return LlmCacheEntry(
        cacheKey = cache_key,
        kind = taskKind,
        promptHash = prompt_hash,
        text = text,
        createdAtMs = created_at,
    )
}

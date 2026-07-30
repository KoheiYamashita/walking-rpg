package com.walkingrpg.shared.data.llm

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.walkingrpg.shared.data.db.WalkingRpgDatabase
import com.walkingrpg.shared.domain.llm.LlmCacheEntry
import com.walkingrpg.shared.domain.llm.LlmTaskKind
import com.walkingrpg.shared.domain.llm.llmCacheKey
import com.walkingrpg.shared.domain.llm.poiFlavorLogicalKey
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * `LlmCache.sq` を実際のSQLite（インメモリ）で検証する。
 * フェイクでは確かめられない「SQLそのものの正しさ」だけをここで見る
 * ＝1論理キー1行に収束するか、種別での絞り込みが効くか。
 */
class LlmCacheRepositoryImplTest {

    private lateinit var database: WalkingRpgDatabase

    private fun repository(): LlmCacheRepositoryImpl {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        WalkingRpgDatabase.Schema.create(driver)
        database = WalkingRpgDatabase(driver)
        return LlmCacheRepositoryImpl(database)
    }

    private fun entry(
        poiId: String,
        text: String,
        promptHash: String = "0123456789abcdef",
        createdAtMs: Long = 1_700_000_000_000L,
    ) = LlmCacheEntry(
        cacheKey = llmCacheKey(LlmTaskKind.POI_FLAVOR, poiFlavorLogicalKey(poiId)),
        kind = LlmTaskKind.POI_FLAVOR,
        promptHash = promptHash,
        text = text,
        createdAtMs = createdAtMs,
    )

    @Test
    fun 保存した行を引ける() = runTest {
        val repository = repository()
        val saved = entry("node/1", "木陰が涼しい。")

        repository.save(saved)

        assertEquals(saved, repository.entry(saved.cacheKey))
    }

    @Test
    fun 未生成のキーはnullで返る() = runTest {
        val repository = repository()

        assertNull(repository.entry(llmCacheKey(LlmTaskKind.POI_FLAVOR, poiFlavorLogicalKey("node/9"))))
    }

    @Test
    fun 同じキーを何度保存しても1行に収束する() = runTest {
        val repository = repository()

        repository.save(entry("node/1", "古い一言。", promptHash = "aaaaaaaaaaaaaaaa"))
        // プロンプトを直して作り直した、の再現
        repository.save(entry("node/1", "新しい一言。", promptHash = "bbbbbbbbbbbbbbbb"))

        val stored = repository.entry(entry("node/1", "").cacheKey)
        assertEquals("新しい一言。", stored?.text)
        assertEquals("bbbbbbbbbbbbbbbb", stored?.promptHash)
        assertEquals(1L, database.llmCacheQueries.countLlmCache().executeAsOne())
    }

    @Test
    fun 種別ごとにまとめて引ける() = runTest {
        val repository = repository()
        val first = entry("node/1", "一。")
        val second = entry("node/2", "二。")
        repository.save(first)
        repository.save(second)
        // 別の種別（#15 以降で増える）が混ざっても拾わないこと
        database.llmCacheQueries.upsertLlmCache(
            cache_key = "OTHER_KIND:poi:node/1",
            kind = "OTHER_KIND",
            prompt_hash = "0123456789abcdef",
            text = "別種別の文章。",
            created_at = 1L,
        )

        val entries = repository.entries(LlmTaskKind.POI_FLAVOR)

        assertEquals(mapOf(first.cacheKey to first, second.cacheKey to second), entries)
    }

    @Test
    fun 知らない種別の行は未生成として扱う() = runTest {
        val repository = repository()
        // 種別を改名した後の古い行。落ちずに「無い」ものとして見えること
        database.llmCacheQueries.upsertLlmCache(
            cache_key = "RETIRED_KIND:poi:node/1",
            kind = "RETIRED_KIND",
            prompt_hash = "0123456789abcdef",
            text = "古い種別の文章。",
            created_at = 1L,
        )

        assertNull(repository.entry("RETIRED_KIND:poi:node/1"))
        assertEquals(emptyMap(), repository.entries(LlmTaskKind.POI_FLAVOR))
    }
}

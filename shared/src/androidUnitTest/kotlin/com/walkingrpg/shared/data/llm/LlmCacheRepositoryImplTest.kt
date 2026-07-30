package com.walkingrpg.shared.data.llm

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.walkingrpg.shared.data.db.WalkingRpgDatabase
import com.walkingrpg.shared.domain.llm.LlmCacheEntry
import com.walkingrpg.shared.domain.llm.LlmTaskKind
import com.walkingrpg.shared.domain.llm.llmCacheKey
import com.walkingrpg.shared.domain.llm.poiFlavorLogicalKey
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * `LlmCache.sq` を実際のSQLite（インメモリ）で検証する。
 * フェイクでは確かめられない「SQLそのものの正しさ」だけをここで見る
 * ＝1論理キー1行に収束するか、種別での絞り込みが効くか。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LlmCacheRepositoryImplTest {

    private lateinit var database: WalkingRpgDatabase

    /**
     * @param dispatcher 購読（`observe`）の流し先。テストスケジューラに乗せると、
     *  保存のあとに `runCurrent()` で決定的に受け取れる。
     */
    private fun repository(
        dispatcher: CoroutineDispatcher = Dispatchers.Default,
    ): LlmCacheRepositoryImpl {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        WalkingRpgDatabase.Schema.create(driver)
        database = WalkingRpgDatabase(driver)
        return LlmCacheRepositoryImpl(database, dispatcher)
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
    fun 購読は保存で流れる() = runTest {
        // 振り返りの遅延差し込み（issue #15）の土台。開いたままの画面へ生成が届く経路
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val repository = repository(dispatcher)
        val saved = entry("node/1", "木陰が涼しい。")

        val emissions = mutableListOf<LlmCacheEntry?>()
        val job = launch(dispatcher) { repository.observe(saved.cacheKey).toList(emissions) }
        runCurrent()

        assertEquals(listOf<LlmCacheEntry?>(null), emissions, "行が無いあいだは null が1回")
        repository.save(saved)
        runCurrent()
        assertEquals(listOf(null, saved), emissions, "保存で流れ直す")
        job.cancel()
    }

    @Test
    fun 購読は他のキーの保存では流れない() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val repository = repository(dispatcher)
        val watched = entry("node/1", "見ている行。")

        val emissions = mutableListOf<LlmCacheEntry?>()
        val job = launch(dispatcher) { repository.observe(watched.cacheKey).toList(emissions) }
        runCurrent()
        repository.save(entry("node/2", "別の行。"))
        runCurrent()

        // SQLDelight は同じテーブルの書き込みで流し直すので、値としては変わらないことを見る
        assertEquals(listOf<LlmCacheEntry?>(null), emissions.distinct())
        job.cancel()
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

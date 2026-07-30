package com.walkingrpg.shared.data.codex

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.walkingrpg.shared.data.db.WalkingRpgDatabase
import com.walkingrpg.shared.domain.codex.CodexProgress
import com.walkingrpg.shared.domain.codex.ForeshadowStage
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `CodexProgress.sq` を実際のSQLite（インメモリ）で検証する。
 * フェイクでは確かめられない「SQLそのものの正しさ」だけをここで見る
 * ＝全削除→挿入で1種1行に収束するか、NULLの `discovered_at` が往復するか。
 */
class CodexProgressRepositoryImplTest {

    private lateinit var database: WalkingRpgDatabase

    private fun repository(): CodexProgressRepositoryImpl {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        WalkingRpgDatabase.Schema.create(driver)
        database = WalkingRpgDatabase(driver)
        return CodexProgressRepositoryImpl(database)
    }

    private fun progress(
        speciesId: String,
        visitCount: Int = 3,
        foreshadowStage: ForeshadowStage = ForeshadowStage.NONE,
        discoveredAtMs: Long? = null,
    ) = CodexProgress(
        speciesId = speciesId,
        visitCount = visitCount,
        foreshadowStage = foreshadowStage,
        discoveredAtMs = discoveredAtMs,
    )

    @Test
    fun 保存した進捗を種ID順に引ける() = runTest {
        val repository = repository()
        val saved = listOf(
            progress("water_kingfisher", visitCount = 10, discoveredAtMs = 1_700_000_000_000L),
            progress("park_turtle_dove", visitCount = 1, foreshadowStage = ForeshadowStage.NEAR),
        )

        repository.replaceAllProgresses(saved)

        assertEquals(saved.sortedBy { it.speciesId }, repository.progresses())
    }

    @Test
    fun 未発見の行は発見時刻がnullで往復する() = runTest {
        val repository = repository()
        val pending = progress("water_kingfisher", visitCount = 8, foreshadowStage = ForeshadowStage.NEAR)

        repository.replaceAllProgresses(listOf(pending))

        val stored = repository.progress("water_kingfisher")
        assertEquals(pending, stored)
        assertNull(stored?.discoveredAtMs)
        assertTrue(!stored!!.isDiscovered)
    }

    @Test
    fun 何度保存しても1種1行に収束する() = runTest {
        val repository = repository()

        repository.replaceAllProgresses(listOf(progress("a", visitCount = 1)))
        repository.replaceAllProgresses(listOf(progress("a", visitCount = 2)))

        assertEquals(1L, database.codexProgressQueries.countCodexProgress().executeAsOne())
        assertEquals(2, repository.progress("a")?.visitCount)
    }

    @Test
    fun 訪問が消えた種の行は残らない() = runTest {
        // 全削除→挿入なので、通っていない場所の生き物は消える（差分更新にしない理由）
        val repository = repository()
        repository.replaceAllProgresses(listOf(progress("a"), progress("b")))

        repository.replaceAllProgresses(listOf(progress("a")))

        assertEquals(listOf("a"), repository.progresses().map { it.speciesId })
    }

    @Test
    fun 空のリストで保存すると空になる() = runTest {
        val repository = repository()
        repository.replaceAllProgresses(listOf(progress("a")))

        repository.replaceAllProgresses(emptyList())

        assertTrue(repository.progresses().isEmpty())
    }

    @Test
    fun 未保存の種はnullで返る() = runTest {
        assertNull(repository().progress("water_kingfisher"))
    }

    @Test
    fun 訪問0回の行は無かったことにする() = runTest {
        // 導出キャッシュなので、壊れた行は次の再計算で入れ替わるのを待つ
        val repository = repository()
        database.codexProgressQueries.insertCodexProgress(
            species_id = "broken",
            visit_count = 0L,
            foreshadow_stage = 0L,
            discovered_at = null,
        )

        assertNull(repository.progress("broken"))
        assertTrue(repository.progresses().isEmpty())
    }

    @Test
    fun 知らない予兆の段はNONEに丸める() = runTest {
        val repository = repository()
        database.codexProgressQueries.insertCodexProgress(
            species_id = "future",
            visit_count = 2L,
            foreshadow_stage = 99L,
            discovered_at = null,
        )

        assertEquals(ForeshadowStage.NONE, repository.progress("future")?.foreshadowStage)
    }

    @Test
    fun 発見済みなのに予兆が立っている行は予兆を落とす() = runTest {
        // CodexProgress の不変条件に反する行でも例外にせず、強い情報（発見済み）を残す
        val repository = repository()
        database.codexProgressQueries.insertCodexProgress(
            species_id = "inconsistent",
            visit_count = 5L,
            foreshadow_stage = 1L,
            discovered_at = 1_700_000_000_000L,
        )

        val stored = repository.progress("inconsistent")
        assertEquals(ForeshadowStage.NONE, stored?.foreshadowStage)
        assertTrue(stored!!.isDiscovered)
    }
}

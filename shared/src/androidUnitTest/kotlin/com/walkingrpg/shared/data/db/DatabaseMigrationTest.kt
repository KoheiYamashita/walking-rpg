package com.walkingrpg.shared.data.db

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.walkingrpg.shared.data.WalkSessionRepositoryImpl
import com.walkingrpg.shared.data.growth.WayGrowthRepositoryImpl
import com.walkingrpg.shared.data.llm.LlmCacheRepositoryImpl
import com.walkingrpg.shared.data.matching.PassageRepositoryImpl
import com.walkingrpg.shared.data.osm.OsmMasterRepositoryImpl
import com.walkingrpg.shared.data.steps.StepImportRepositoryImpl
import com.walkingrpg.shared.data.weather.SessionWeatherRepositoryImpl
import com.walkingrpg.shared.domain.growth.GrowthStage
import com.walkingrpg.shared.domain.llm.LlmCacheEntry
import com.walkingrpg.shared.domain.llm.LlmTaskKind
import com.walkingrpg.shared.domain.llm.llmCacheKey
import com.walkingrpg.shared.domain.llm.poiFlavorLogicalKey
import com.walkingrpg.shared.domain.growth.WayGrowth
import com.walkingrpg.shared.domain.matching.Passage
import com.walkingrpg.shared.domain.matching.SyntheticWalk
import com.walkingrpg.shared.domain.steps.CalendarDay
import com.walkingrpg.shared.domain.steps.StepImport
import com.walkingrpg.shared.domain.walk.SessionEndReason
import com.walkingrpg.shared.domain.weather.SessionWeather
import com.walkingrpg.shared.domain.weather.WeatherCondition
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * アプリを更新しただけの端末（＝アンインストールしていない端末）で
 * スキーマが追従することを、実際のSQLiteファイルで検証する。
 *
 * 見たいのは「新しい .sq を書いただけでは既存DBに新テーブルが作られない」という
 * 事故（PR #30 / #31 のレビュー指摘）が二度と起きないこと。
 * 起点には `databases/1.db`（コミットしてあるスパイク期のスキーマ）をそのまま使う。
 * テストの中でv1のDDLを書き直すと、検証しているのが「テストが書いたスキーマ」に
 * なってしまい、端末に入っている本物のスキーマから出発したことにならない。
 */
class DatabaseMigrationTest {

    /**
     * スパイク期のスキーマ（v1）。`shared/` からの相対パスで、
     * verifyMigrations が読むのと同じファイルを指す。
     */
    private val schemaV1File = File("src/commonMain/sqldelight/databases/1.db")

    /**
     * v1のDBファイルの複製を開く。
     *
     * 端末側は AndroidSqliteDriver / NativeSqliteDriver が `user_version` を見て
     * [WalkingRpgDatabase.Schema] の `migrate` を呼ぶが、テスト用の JdbcSqliteDriver は
     * バージョン管理をしないので、その役目をこのテストが肩代わりする。
     */
    private fun openV1Copy(): JdbcSqliteDriver {
        assertTrue(schemaV1File.isFile, "v1のスキーマファイルが見つからない: ${schemaV1File.absolutePath}")
        val copy = File.createTempFile("walking_rpg_v1_", ".db").also { it.deleteOnExit() }
        schemaV1File.copyTo(copy, overwrite = true)
        return JdbcSqliteDriver("jdbc:sqlite:${copy.absolutePath}")
    }

    /** 端末のドライバがやるのと同じ「差分ぶんの .sqm を流してバージョンを進める」処理。 */
    private fun SqlDriver.upgradeToCurrentSchema() {
        val from = userVersion()
        WalkingRpgDatabase.Schema.migrate(this, from, WalkingRpgDatabase.Schema.version).value
        execute(null, "PRAGMA user_version = ${WalkingRpgDatabase.Schema.version}", 0)
    }

    private fun SqlDriver.userVersion(): Long = executeQuery(
        identifier = null,
        sql = "PRAGMA user_version",
        mapper = { cursor ->
            cursor.next().value
            QueryResult.Value(cursor.getLong(0) ?: 0L)
        },
        parameters = 0,
    ).value

    private fun SqlDriver.tableNames(): List<String> = executeQuery(
        identifier = null,
        sql = "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name",
        mapper = { cursor ->
            val names = mutableListOf<String>()
            while (cursor.next().value) {
                names += cursor.getString(0)!!
            }
            QueryResult.Value(names.toList())
        },
        parameters = 0,
    ).value

    @Test
    fun 旧バージョンのDBを更新すると本実装のテーブルが増える() {
        val driver = openV1Copy()

        assertEquals(1L, driver.userVersion(), "起点はスパイク期のv1")
        assertEquals(
            listOf("location_sample", "walk_session"),
            driver.tableNames(),
            "v1には歩行ログしか無い",
        )

        driver.upgradeToCurrentSchema()

        assertEquals(WalkingRpgDatabase.Schema.version, driver.userVersion())
        assertEquals(
            listOf(
                "llm_cache",
                "location_sample",
                "passage",
                "poi",
                "session_weather",
                "step_import",
                "walk_session",
                "way",
                "way_growth",
            ),
            driver.tableNames(),
            "way / poi / passage / way_growth（1.sqm）・step_import（2.sqm）・" +
                "session_weather（3.sqm）・llm_cache（4.sqm）が足される",
        )
    }

    @Test
    fun 更新後のスキーマは新規インストールと同じ形になる() {
        val migrated = openV1Copy().also { it.upgradeToCurrentSchema() }
        val fresh = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            .also { WalkingRpgDatabase.Schema.create(it).value }

        // 索引まで含めて比べる。索引の付け忘れはクエリが通るぶん気付きにくい。
        assertEquals(schemaObjects(fresh), schemaObjects(migrated))
    }

    @Test
    fun 更新しても歩行ログは消えない() = runTest {
        val driver = openV1Copy()

        // 「更新前の端末に残っている散歩」を、v1の時点のテーブルだけを使って作る。
        val beforeUpgrade = WalkSessionRepositoryImpl(WalkingRpgDatabase(driver))
        val sessionId = beforeUpgrade.startSession(startedAtMs = SyntheticWalk.START_MS)
        val samples = SyntheticWalk.samples(
            points = List(5) { index -> SyntheticWalk.point(0.0, 20.0 + index * 2.8) },
        ).map { it.copy(sessionId = sessionId) }
        samples.forEach { beforeUpgrade.appendSample(it) }

        driver.upgradeToCurrentSchema()

        val afterUpgrade = WalkSessionRepositoryImpl(WalkingRpgDatabase(driver))
        assertEquals(samples, afterUpgrade.samples(sessionId), "真実の源はマイグレーションで触らない")
    }

    @Test
    fun 更新後の端末で新テーブルが読み書きできる() = runTest {
        val driver = openV1Copy().also { it.upgradeToCurrentSchema() }
        val database = WalkingRpgDatabase(driver)
        val sessions = WalkSessionRepositoryImpl(database)
        val sessionId = sessions.startSession(startedAtMs = SyntheticWalk.START_MS)

        val way = SyntheticWalk.eastWestWay(id = 1L, northMeters = 0.0, fromEast = 0.0, toEast = 300.0)
        OsmMasterRepositoryImpl(database).save(ways = listOf(way), pois = emptyList())

        val passages = PassageRepositoryImpl(database)
        passages.replaceSessionPassages(
            sessionId = sessionId,
            passages = listOf(Passage(sessionId = sessionId, wayId = way.id, timestampMs = SyntheticWalk.START_MS)),
        )
        val growths = WayGrowthRepositoryImpl(database)
        growths.replaceAllGrowths(listOf(WayGrowth(wayId = way.id, passCount = 1, stage = GrowthStage.GRASS)))

        assertEquals(listOf(way.id), passages.passages(sessionId).map { it.wayId })
        assertEquals(listOf(way.id), growths.growths().map { it.wayId })

        // 押し忘れ救済（2.sqm で足した step_import）。更新しただけの端末でも書き込める
        val day = CalendarDay("2026-03-01")
        val stepImports = StepImportRepositoryImpl(database)
        stepImports.upsert(StepImport(day = day, steps = 8_200, distanceEstimateMeters = 6_100.0))
        assertEquals(8_200, stepImports.stepImport(day)?.steps)

        // 天候の後付け確定（3.sqm で足した session_weather）。
        // 更新しただけの端末でも、過去の散歩が「未取得」として拾えて書き込める
        val weathers = SessionWeatherRepositoryImpl(database)
        assertEquals(emptyList(), weathers.sessionIdsWithoutWeather(), "まだ終わっていない散歩")
        sessions.endSession(sessionId, SyntheticWalk.START_MS + 60_000L, SessionEndReason.MANUAL)
        assertEquals(listOf(sessionId), weathers.sessionIdsWithoutWeather())
        weathers.save(
            SessionWeather(
                sessionId = sessionId,
                condition = WeatherCondition.RAIN,
                temperatureCelsius = 13.0,
                fetchedAtMs = SyntheticWalk.START_MS + 120_000L,
            ),
        )
        assertEquals(WeatherCondition.RAIN, weathers.weather(sessionId)?.condition)
        assertEquals(emptyList(), weathers.sessionIdsWithoutWeather())

        // LLM生成テキストのキャッシュ（4.sqm で足した llm_cache）。
        // 更新しただけの端末でも、未生成として拾えて書き込める
        val flavors = LlmCacheRepositoryImpl(database)
        val cacheKey = llmCacheKey(LlmTaskKind.POI_FLAVOR, poiFlavorLogicalKey("node/1"))
        assertNull(flavors.entry(cacheKey), "更新直後は1件も生成されていない")
        flavors.save(
            LlmCacheEntry(
                cacheKey = cacheKey,
                kind = LlmTaskKind.POI_FLAVOR,
                promptHash = "0123456789abcdef",
                text = "木陰が涼しい。",
                createdAtMs = SyntheticWalk.START_MS + 180_000L,
            ),
        )
        assertEquals("木陰が涼しい。", flavors.entry(cacheKey)?.text)
    }

    @Test
    fun 本実装のテーブルが既にあるv1のDBでも更新が失敗しない() {
        // スパイク期はバージョンを上げずにテーブルを足していたので、
        // 同じ user_version = 1 でも way / passage まで入っている端末が実在する。
        // 1.sqm の IF NOT EXISTS はそこを通すためのもの（1.sqm 参照）。
        val driver = openV1Copy()
        driver.execute(
            null,
            "CREATE TABLE passage (session_id INTEGER NOT NULL, way_id INTEGER NOT NULL, ts INTEGER NOT NULL)",
            0,
        )
        driver.execute(null, "CREATE INDEX passage_way ON passage(way_id)", 0)

        driver.upgradeToCurrentSchema()

        assertEquals(
            listOf(
                "llm_cache",
                "location_sample",
                "passage",
                "poi",
                "session_weather",
                "step_import",
                "walk_session",
                "way",
                "way_growth",
            ),
            driver.tableNames(),
        )
    }

    private fun schemaObjects(driver: SqlDriver): List<String> = driver.executeQuery(
        identifier = null,
        sql = """
            SELECT type || ' ' || name FROM sqlite_master
            WHERE name NOT LIKE 'sqlite_%'
            ORDER BY type, name
        """.trimIndent(),
        mapper = { cursor ->
            val rows = mutableListOf<String>()
            while (cursor.next().value) {
                rows += cursor.getString(0)!!
            }
            QueryResult.Value(rows.toList())
        },
        parameters = 0,
    ).value
}

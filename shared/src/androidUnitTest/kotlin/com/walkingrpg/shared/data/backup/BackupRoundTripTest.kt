package com.walkingrpg.shared.data.backup

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.walkingrpg.shared.data.WalkSessionRepositoryImpl
import com.walkingrpg.shared.data.codex.CodexProgressRepositoryImpl
import com.walkingrpg.shared.data.db.WalkingRpgDatabase
import com.walkingrpg.shared.data.growth.WayGrowthRepositoryImpl
import com.walkingrpg.shared.data.llm.LlmCacheRepositoryImpl
import com.walkingrpg.shared.data.llm.UtteranceLogRepositoryImpl
import com.walkingrpg.shared.data.matching.PassageRepositoryImpl
import com.walkingrpg.shared.data.osm.OsmMasterRepositoryImpl
import com.walkingrpg.shared.data.snapshot.SnapshotRepositoryImpl
import com.walkingrpg.shared.data.steps.StepImportRepositoryImpl
import com.walkingrpg.shared.data.weather.SessionWeatherRepositoryImpl
import com.walkingrpg.shared.domain.backup.BackupData
import com.walkingrpg.shared.domain.codex.RecomputeCodexProgressUseCase
import com.walkingrpg.shared.domain.codex.codexPoi
import com.walkingrpg.shared.domain.growth.RecomputeWayGrowthUseCase
import com.walkingrpg.shared.domain.llm.LlmCacheEntry
import com.walkingrpg.shared.domain.llm.LlmTaskKind
import com.walkingrpg.shared.domain.llm.RemarkAngle
import com.walkingrpg.shared.domain.llm.UtteranceRecord
import com.walkingrpg.shared.domain.matching.Passage
import com.walkingrpg.shared.domain.matching.SyntheticWalk
import com.walkingrpg.shared.domain.osm.Poi
import com.walkingrpg.shared.domain.osm.PoiKind
import com.walkingrpg.shared.domain.osm.Way
import com.walkingrpg.shared.domain.snapshot.CalendarMonth
import com.walkingrpg.shared.domain.snapshot.FakeSnapshotImageStore
import com.walkingrpg.shared.domain.snapshot.MonthlySnapshotStats
import com.walkingrpg.shared.domain.snapshot.Snapshot
import com.walkingrpg.shared.domain.steps.CalendarDay
import com.walkingrpg.shared.domain.steps.StepImport
import com.walkingrpg.shared.domain.walk.SessionEndReason
import com.walkingrpg.shared.domain.weather.SessionWeather
import com.walkingrpg.shared.domain.weather.WeatherCondition
import com.walkingrpg.shared.platform.AndroidBackupArchive
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 手動エクスポート → インポートの往復（issue #18・architecture.md §6）を
 * 実際のSQLite（インメモリ）と実際のzip（`java.util.zip`）で検証する。
 *
 * 見たいのは issue #18 の完了条件そのもの：
 * - 端末Aで書き出したzipを端末Bで取り込むと**全テーブル・画像が完全に一致する**
 * - 導出（`way_growth` / `codex_progress`）を作り直しても同じ状態になる
 * - 同じ端末で「全削除 → インポート」しても元に戻る
 *
 * 座標はすべて架空（[SyntheticWalk]。CONTRIBUTING.md「位置情報の扱い」）。
 */
class BackupRoundTripTest {

    private val archive = AndroidBackupArchive()
    private val codec = BackupArchiveCodec()
    private val exportedAtMs = SyntheticWalk.START_MS + 7_200_000L

    /** 対象圏のマスタ。**バックアップには入らない**ので、両方の端末に別途取り込む。 */
    private val mainStreet: Way =
        SyntheticWalk.eastWestWay(id = 1L, northMeters = 0.0, fromEast = 0.0, toEast = 300.0)
    private val crossStreet: Way =
        SyntheticWalk.northSouthWay(id = 2L, eastMeters = 300.0, fromNorth = 0.0, toNorth = 300.0)

    /** 水辺の棚（`SpeciesCatalog` の WATER）を埋めるためのPOI。道の上に置く。 */
    private val pond: Poi =
        codexPoi(id = "node/1", kind = PoiKind.WATER, northMeters = 0.0, eastMeters = 150.0)

    private val juneImage = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x00, 0xFF.toByte(), 0x01)
    private val mayImage = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x7F, 0x80.toByte())

    private class Fixture {
        val database = WalkingRpgDatabase(
            JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also(WalkingRpgDatabase.Schema::create),
        )
        val images = FakeSnapshotImageStore()
        val store = BackupStoreImpl(database, images)

        val sessions = WalkSessionRepositoryImpl(database)
        val passages = PassageRepositoryImpl(database)
        val master = OsmMasterRepositoryImpl(database)
        val growths = WayGrowthRepositoryImpl(database)
        val codex = CodexProgressRepositoryImpl(database)
        val stepImports = StepImportRepositoryImpl(database)
        val weathers = SessionWeatherRepositoryImpl(database)
        val utterances = UtteranceLogRepositoryImpl(database)
        val llmCache = LlmCacheRepositoryImpl(database)
        val snapshots = SnapshotRepositoryImpl(database)

        val recomputeGrowth = RecomputeWayGrowthUseCase(
            passageRepository = passages,
            wayGrowthRepository = growths,
        )
        val recomputeCodex = RecomputeCodexProgressUseCase(
            passageRepository = passages,
            osmMasterRepository = master,
            codexProgressRepository = codex,
        )
    }

    /** 対象圏のマスタを入れる（インポートの前提。`ImportBackupUseCase` のKDoc）。 */
    private suspend fun Fixture.importMaster() {
        master.save(ways = listOf(mainStreet, crossStreet), pois = listOf(pond))
    }

    /**
     * 全テーブルに中身のある端末を作る。
     *
     * 「行が1件もない表」が混ざると往復が通っても意味が薄いので、
     * バックアップ対象の8つの表すべてに行を入れる。
     */
    private suspend fun populated(): Fixture = Fixture().apply {
        importMaster()

        // --- 真実の源：2回の散歩（1つは終了済み、1つは記録中） ---
        val first = sessions.startSession(startedAtMs = SyntheticWalk.START_MS)
        SyntheticWalk.samples(
            points = List(20) { index -> SyntheticWalk.point(0.0, 20.0 + index * 2.8) },
        ).forEach { sample -> sessions.appendSample(sample.copy(sessionId = first)) }
        sessions.endSession(
            sessionId = first,
            endedAtMs = SyntheticWalk.START_MS + 1_800_000L,
            reason = SessionEndReason.AUTO_ARRIVAL,
        )

        val second = sessions.startSession(startedAtMs = SyntheticWalk.START_MS + 86_400_000L)
        SyntheticWalk.samples(
            points = List(10) { index -> SyntheticWalk.point(5.0 + index * 2.8, 300.0) },
            startMs = SyntheticWalk.START_MS + 86_400_000L,
        ).forEach { sample -> sessions.appendSample(sample.copy(sessionId = second)) }

        // --- passage（再マッチせずそのまま戻す対象） ---
        passages.replaceSessionPassages(
            first,
            listOf(
                Passage(sessionId = first, wayId = mainStreet.id, timestampMs = SyntheticWalk.START_MS),
                // 同じ散歩で同じ道を2回通る（pass_count は通過ごと）
                Passage(
                    sessionId = first,
                    wayId = mainStreet.id,
                    timestampMs = SyntheticWalk.START_MS + 600_000L,
                ),
            ),
        )
        passages.replaceSessionPassages(
            second,
            listOf(
                Passage(
                    sessionId = second,
                    wayId = crossStreet.id,
                    timestampMs = SyntheticWalk.START_MS + 86_400_000L,
                ),
                Passage(
                    sessionId = second,
                    wayId = mainStreet.id,
                    timestampMs = SyntheticWalk.START_MS + 86_700_000L,
                ),
            ),
        )

        // --- 押し忘れ救済 ---
        stepImports.upsert(
            StepImport(CalendarDay("2026-06-01"), steps = 8_123, distanceEstimateMeters = 6_400.0),
        )
        stepImports.upsert(
            StepImport(CalendarDay("2026-06-02"), steps = 3_000, distanceEstimateMeters = null),
        )

        // --- 天候（外部APIの観測値） ---
        weathers.save(
            SessionWeather(
                sessionId = first,
                condition = WeatherCondition.CLOUDY,
                temperatureCelsius = 21.5,
                fetchedAtMs = exportedAtMs,
            ),
        )
        weathers.save(
            SessionWeather(
                sessionId = second,
                condition = WeatherCondition.RAIN,
                temperatureCelsius = null,
                fetchedAtMs = exportedAtMs,
            ),
        )

        // --- 発話履歴（再計算できない追記ログ） ---
        utterances.replaceSessionUtterances(
            first,
            listOf(
                UtteranceRecord(first, "way:1", RemarkAngle.GROWTH, SyntheticWalk.START_MS),
            ),
        )
        utterances.replaceSessionUtterances(
            second,
            listOf(
                UtteranceRecord(
                    second,
                    "day",
                    RemarkAngle.WEATHER,
                    SyntheticWalk.START_MS + 86_400_000L,
                ),
            ),
        )

        // --- 生成キャッシュ（再課金を避けるため入れる） ---
        llmCache.save(
            LlmCacheEntry(
                cacheKey = "POI_FLAVOR:poi:node/1",
                kind = LlmTaskKind.POI_FLAVOR,
                promptHash = "0123456789abcdef",
                text = "テスト用の情景。",
                createdAtMs = exportedAtMs,
            ),
        )
        llmCache.save(
            LlmCacheEntry(
                cacheKey = "WALK_REVIEW_REMARK:session:$first",
                kind = LlmTaskKind.WALK_REVIEW_REMARK,
                promptHash = "fedcba9876543210",
                text = "テスト用の一言。",
                createdAtMs = exportedAtMs,
            ),
        )

        // --- アルバム（画像 → 行の順で書く） ---
        saveSnapshot(CalendarMonth("2026-05"), mayImage, distanceMeters = 11_000.0)
        saveSnapshot(CalendarMonth("2026-06"), juneImage, distanceMeters = 23_400.5)
    }

    private suspend fun Fixture.saveSnapshot(
        month: CalendarMonth,
        bytes: ByteArray,
        distanceMeters: Double,
    ) {
        val path = "snapshots/${month.iso}.png"
        images.write(path, bytes)
        snapshots.insertIfAbsent(
            Snapshot(
                month = month,
                imagePath = path,
                stats = MonthlySnapshotStats(
                    distanceMeters = distanceMeters,
                    newWayCount = 2,
                    discoveredSpeciesNames = listOf("テストトンボ"),
                    sessionCount = 3,
                ),
                createdAtMs = exportedAtMs,
            ),
        )
    }

    /** エクスポート：DBの中身を実際のzipのバイト列にする。 */
    private suspend fun export(fixture: Fixture): ByteArray =
        archive.write(codec.encode(fixture.store.read(), exportedAtMs))

    /** インポート：zipのバイト列をDBに入れる（導出は呼び出し側で作り直す）。 */
    private suspend fun import(fixture: Fixture, zipBytes: ByteArray) {
        fixture.store.replaceAll(codec.decode(archive.read(zipBytes)))
    }

    // --- 端末A → 端末B ---

    @Test
    fun 別の端末に取り込むと全テーブルと画像が一致する() = runTest {
        val source = populated()
        val zipBytes = export(source)

        val target = Fixture()
        target.importMaster()
        import(target, zipBytes)

        // BackupData は全テーブル＋画像のバイト列を含むので、これ1つで完全一致を見られる
        assertEquals(source.store.read(), target.store.read())
    }

    @Test
    fun セッションIDが保たれるので参照がずれない() = runTest {
        val source = populated()
        val target = Fixture().apply { importMaster() }

        import(target, export(source))

        val sourceIds = source.store.read().sessions.map { it.id }
        assertEquals(listOf(1L, 2L), sourceIds, "AUTOINCREMENT の前提が変わっている")
        assertEquals(sourceIds, target.store.read().sessions.map { it.id })
        // 参照する側（サンプル・通過・天候・発話・キャッシュ）が同じIDを指している
        assertEquals(
            source.store.read().samples.map { it.sessionId },
            target.store.read().samples.map { it.sessionId },
        )
        assertEquals(
            listOf(1L, 1L, 2L, 2L),
            target.store.read().passages.map { it.sessionId },
        )
    }

    @Test
    fun 記録中のセッションも終了済みのセッションも同じ形で戻る() = runTest {
        val source = populated()
        val target = Fixture().apply { importMaster() }

        import(target, export(source))

        val restored = target.store.read().sessions
        assertEquals(SessionEndReason.AUTO_ARRIVAL, restored.first().endReason)
        assertEquals(null, restored.last().endedAtMs, "記録中の散歩が終了済みになっている")
        assertEquals(null, restored.last().endReason)
    }

    @Test
    fun 画像のバイト列がそのまま置き場に戻る() = runTest {
        val source = populated()
        val target = Fixture().apply { importMaster() }

        import(target, export(source))

        assertContentEquals(juneImage, target.images.read("snapshots/2026-06.png"))
        assertContentEquals(mayImage, target.images.read("snapshots/2026-05.png"))
        assertEquals(
            setOf("snapshots/2026-05.png", "snapshots/2026-06.png"),
            target.images.paths,
        )
    }

    @Test
    fun 画像はDBの行より先に書かれる() = runTest {
        // 行だけあって画像が無い「アルバム割れ」を作らない（Snapshot.sq）
        val source = populated()
        val target = Fixture().apply { importMaster() }

        import(target, export(source))

        target.snapshots.snapshots().forEach { snapshot ->
            assertTrue(
                snapshot.imagePath in target.images.paths,
                "行だけ入って画像が無い: ${snapshot.imagePath}",
            )
        }
    }

    // --- 導出の作り直し ---

    @Test
    fun 復元後に導出を作り直すと元の端末と同じ状態になる() = runTest {
        val source = populated()
        val sourceGrowths = source.recomputeGrowth()
        val sourceCodex = source.recomputeCodex()
        // 導出そのものはzipに入らないので、中身があることを先に確かめる
        assertTrue(sourceGrowths.isNotEmpty(), "成長が0件では比較の意味がない")
        assertTrue(sourceCodex.isNotEmpty(), "図鑑進捗が0件では比較の意味がない")

        val target = Fixture().apply { importMaster() }
        import(target, export(source))

        assertEquals(sourceGrowths, target.recomputeGrowth())
        assertEquals(sourceCodex, target.recomputeCodex())
        assertEquals(sourceGrowths, target.growths.growths())
        assertEquals(sourceCodex, target.codex.progresses())
    }

    @Test
    fun passageは再マッチせずそのまま復元される() = runTest {
        val source = populated()
        val target = Fixture().apply { importMaster() }

        import(target, export(source))

        // 本通り3回・曲がった先1回（populated が入れた通過そのまま）
        assertEquals(
            listOf(1L to 3, 2L to 1),
            target.recomputeGrowth().map { it.wayId to it.passCount },
        )
    }

    // --- 同じ端末での全削除 → 復元 ---

    @Test
    fun 同じ端末で全削除してからインポートすると元に戻る() = runTest {
        val fixture = populated()
        val before = fixture.store.read()
        val zipBytes = export(fixture)

        // 「機種変更ではなく、この端末を壊してから戻す」経路
        fixture.store.replaceAll(EMPTY_BACKUP)
        assertEquals(EMPTY_BACKUP, fixture.store.read(), "全削除できていない")
        assertTrue(fixture.images.paths.isEmpty(), "使われなくなった画像が消えていない")

        import(fixture, zipBytes)

        assertEquals(before, fixture.store.read())
        assertContentEquals(juneImage, fixture.images.read("snapshots/2026-06.png"))
    }

    @Test
    fun インポートは今ある記録を残さず置き換える() = runTest {
        val source = populated()
        val zipBytes = export(source)

        // 取り込み先にも別の散歩がある状態から始める
        val target = populated()
        target.sessions.startSession(startedAtMs = SyntheticWalk.START_MS + 172_800_000L)
        target.stepImports.upsert(
            StepImport(CalendarDay("2026-07-01"), steps = 1, distanceEstimateMeters = null),
        )
        target.saveSnapshot(CalendarMonth("2026-07"), byteArrayOf(0x01), distanceMeters = 1.0)

        import(target, zipBytes)

        assertEquals(source.store.read(), target.store.read(), "取り込み先の記録が残っている")
        assertTrue(
            "snapshots/2026-07.png" !in target.images.paths,
            "使われなくなった画像が残っている",
        )
    }

    @Test
    fun 空の端末から書き出したzipも取り込める() = runTest {
        // 散歩が1件も無い状態でエクスポート → インポートしても落ちない
        val source = Fixture().apply { importMaster() }
        val target = populated()

        import(target, export(source))

        assertEquals(EMPTY_BACKUP, target.store.read())
        assertTrue(target.images.paths.isEmpty())
    }

    @Test
    fun 検証に失敗したときはDBに何も書かれない() = runTest {
        val fixture = populated()
        val before = fixture.store.read()
        // 必須エントリを落としたzip（転送で壊れたファイル）
        val broken = archive.write(
            archive.read(export(fixture)).filterNot { it.name == "passages.json" },
        )

        runCatching { import(fixture, broken) }

        assertEquals(before, fixture.store.read(), "壊れたzipで記録が消えている")
    }

    private companion object {
        /** 何も入っていない状態（全削除の期待値）。 */
        val EMPTY_BACKUP = BackupData(
            sessions = emptyList(),
            samples = emptyList(),
            passages = emptyList(),
            stepImports = emptyList(),
            sessionWeathers = emptyList(),
            utterances = emptyList(),
            llmCacheEntries = emptyList(),
            snapshots = emptyList(),
            snapshotImages = emptyList(),
        )
    }
}

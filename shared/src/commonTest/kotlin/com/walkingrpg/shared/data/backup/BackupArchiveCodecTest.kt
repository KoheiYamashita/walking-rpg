package com.walkingrpg.shared.data.backup

import com.walkingrpg.shared.domain.backup.BackupData
import com.walkingrpg.shared.domain.backup.BackupFormatException
import com.walkingrpg.shared.domain.backup.BackupSnapshotImage
import com.walkingrpg.shared.domain.llm.LlmCacheEntry
import com.walkingrpg.shared.domain.llm.LlmTaskKind
import com.walkingrpg.shared.domain.llm.RemarkAngle
import com.walkingrpg.shared.domain.llm.UtteranceRecord
import com.walkingrpg.shared.domain.matching.Passage
import com.walkingrpg.shared.domain.snapshot.CalendarMonth
import com.walkingrpg.shared.domain.snapshot.MonthlySnapshotStats
import com.walkingrpg.shared.domain.snapshot.Snapshot
import com.walkingrpg.shared.domain.steps.CalendarDay
import com.walkingrpg.shared.domain.steps.StepImport
import com.walkingrpg.shared.domain.walk.LocationSample
import com.walkingrpg.shared.domain.walk.SessionEndReason
import com.walkingrpg.shared.domain.walk.WalkSession
import com.walkingrpg.shared.domain.weather.SessionWeather
import com.walkingrpg.shared.domain.weather.WeatherCondition
import com.walkingrpg.shared.platform.BackupEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * zipの中身の形（[BackupArchiveCodec]）を検証する。zipそのものは通さない
 * （`AndroidBackupArchive` の担当）ので、エントリ列を直接組んで壊せる。
 *
 * 見たいのは2つ：
 * - JSONを往復してドメインの値がそのまま戻ること
 * - **壊れたファイルを必ず弾くこと**（`manifest.json` の版・必須エントリ・件数・画像）。
 *   インポートは破壊的なので、ここで弾けなかったら全削除だけが走る
 *
 * 座標はすべて架空（CONTRIBUTING.md「位置情報の扱い」）。
 */
class BackupArchiveCodecTest {

    private val codec = BackupArchiveCodec()
    private val exportedAtMs = 1_767_225_600_000L

    private val imageBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x00, 0xFF.toByte())

    private val data = BackupData(
        sessions = listOf(
            WalkSession(
                id = 1L,
                startedAtMs = exportedAtMs - 3_600_000L,
                endedAtMs = exportedAtMs - 1_800_000L,
                endReason = SessionEndReason.AUTO_ARRIVAL,
            ),
            // 記録中（ended_at / end_reason が NULL）の行も往復する
            WalkSession(id = 2L, startedAtMs = exportedAtMs - 600_000L),
        ),
        samples = listOf(
            LocationSample(
                sessionId = 1L,
                timestampMs = exportedAtMs - 3_600_000L,
                latitude = 12.345678,
                longitude = 98.765432,
                accuracyMeters = 8.5,
            ),
        ),
        passages = listOf(Passage(sessionId = 1L, wayId = 42L, timestampMs = exportedAtMs)),
        stepImports = listOf(
            StepImport(day = CalendarDay("2026-06-01"), steps = 8_123, distanceEstimateMeters = 6_400.0),
            // 距離を返さない歩数計（null）も往復する
            StepImport(day = CalendarDay("2026-06-02"), steps = 3_000, distanceEstimateMeters = null),
        ),
        sessionWeathers = listOf(
            SessionWeather(
                sessionId = 1L,
                condition = WeatherCondition.CLOUDY,
                temperatureCelsius = 21.5,
                fetchedAtMs = exportedAtMs,
            ),
        ),
        utterances = listOf(
            UtteranceRecord(
                sessionId = 1L,
                placeRef = "way:42",
                angle = RemarkAngle.GROWTH,
                saidAtMs = exportedAtMs - 3_600_000L,
            ),
        ),
        llmCacheEntries = listOf(
            LlmCacheEntry(
                cacheKey = "POI_FLAVOR:poi:node/1",
                kind = LlmTaskKind.POI_FLAVOR,
                promptHash = "0123456789abcdef",
                text = "テスト用の生成文。",
                createdAtMs = exportedAtMs,
            ),
        ),
        snapshots = listOf(
            Snapshot(
                month = CalendarMonth("2026-06"),
                imagePath = "snapshots/2026-06.png",
                stats = MonthlySnapshotStats(
                    distanceMeters = 23_400.5,
                    newWayCount = 12,
                    discoveredSpeciesNames = listOf("テストトンボ"),
                    sessionCount = 9,
                ),
                createdAtMs = exportedAtMs,
            ),
        ),
        snapshotImages = listOf(
            BackupSnapshotImage(relativePath = "snapshots/2026-06.png", bytes = imageBytes),
        ),
    )

    private fun encoded(): List<BackupEntry> = codec.encode(data, exportedAtMs)

    // --- 往復 ---

    @Test
    fun 全テーブルがJSONを往復して戻る() {
        val restored = codec.decode(encoded())

        assertEquals(data.sessions, restored.sessions)
        assertEquals(data.samples, restored.samples, "座標が往復で変わっている")
        assertEquals(data.passages, restored.passages)
        assertEquals(data.stepImports, restored.stepImports)
        assertEquals(data.sessionWeathers, restored.sessionWeathers)
        assertEquals(data.utterances, restored.utterances)
        assertEquals(data.llmCacheEntries, restored.llmCacheEntries)
        assertEquals(data.snapshots, restored.snapshots, "stats_json が往復して戻る")
        assertEquals(data.snapshotImages, restored.snapshotImages, "画像のバイト列が変わっている")
    }

    @Test
    fun 空のバックアップも往復する() {
        val empty = BackupData(
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

        assertEquals(empty, codec.decode(codec.encode(empty, exportedAtMs)))
    }

    @Test
    fun zipの構成は決めた形のまま() {
        // 名前を変えると古いバックアップが読めなくなるので、契約として固定する
        assertEquals(
            listOf(
                "manifest.json",
                "walk_sessions.json",
                "location_samples.json",
                "passages.json",
                "step_imports.json",
                "session_weathers.json",
                "utterance_logs.json",
                "llm_cache.json",
                "snapshots.json",
                "snapshots/2026-06.png",
            ),
            encoded().map { it.name },
        )
    }

    @Test
    fun 画像のエントリ名はimage_pathと同じ相対パス() {
        // 復元先を計算し直さないための決まり（Snapshot.sq「絶対パスは持たない」）
        val imageEntry = encoded().last()

        assertEquals(data.snapshots.single().imagePath, imageEntry.name)
    }

    // --- 検証（何も書かずに失敗する） ---

    @Test
    fun スキーマ版が違えば弾く() {
        val tampered = replaceManifest(
            encoded(),
            """{"schemaVersion":2,"exportedAtMs":$exportedAtMs,"counts":${counts()}}""",
        )

        val error = assertFailsWith<BackupFormatException> { codec.decode(tampered) }
        assertTrue("版" in error.message, error.message)
    }

    @Test
    fun manifestが無ければ弾く() {
        val tampered = encoded().filterNot { it.name == "manifest.json" }

        assertFailsWith<BackupFormatException> { codec.decode(tampered) }
    }

    @Test
    fun 必須エントリが欠けていれば弾く() {
        // 「無い＝0件」として通すと、壊れたzipで真実の源が消える
        listOf(
            "walk_sessions.json",
            "location_samples.json",
            "passages.json",
            "step_imports.json",
            "session_weathers.json",
            "utterance_logs.json",
            "llm_cache.json",
            "snapshots.json",
        ).forEach { missing ->
            val tampered = encoded().filterNot { it.name == missing }

            val error = assertFailsWith<BackupFormatException>("$missing の欠落が通っている") {
                codec.decode(tampered)
            }
            assertTrue(missing in error.message, error.message)
        }
    }

    @Test
    fun 件数がmanifestと合わなければ弾く() {
        // JSONの配列が途中で切れた（転送が失敗した）zipを書き込む前に落とす
        val tampered = replaceManifest(
            encoded(),
            """{"schemaVersion":1,"exportedAtMs":$exportedAtMs,""" +
                """"counts":${counts(sampleCount = 99)}}""",
        )

        val error = assertFailsWith<BackupFormatException> { codec.decode(tampered) }
        assertTrue("件数" in error.message, error.message)
    }

    @Test
    fun スナップショットの画像が無ければ弾く() {
        // 行だけ復元して画像が無い「アルバム割れ」を作らない
        val tampered = encoded().filterNot { it.name == "snapshots/2026-06.png" }

        val error = assertFailsWith<BackupFormatException> { codec.decode(tampered) }
        assertTrue("snapshots/2026-06.png" in error.message, error.message)
    }

    @Test
    fun JSONが壊れていれば弾く() {
        val tampered = encoded().map { entry ->
            if (entry.name == "walk_sessions.json") {
                BackupEntry(entry.name, "これはJSONではない".encodeToByteArray())
            } else {
                entry
            }
        }

        assertFailsWith<BackupFormatException> { codec.decode(tampered) }
    }

    @Test
    fun 月の形式が壊れていれば弾く() {
        // ドメインの require（CalendarMonth）で落ちるものも同じ扱いにする
        val tampered = encoded().map { entry ->
            if (entry.name == "snapshots.json") {
                BackupEntry(
                    entry.name,
                    (
                        """[{"month":"2026-6","imagePath":"snapshots/2026-06.png",""" +
                            """"statsJson":"{}","createdAt":1}]"""
                        ).encodeToByteArray(),
                )
            } else {
                entry
            }
        }

        assertFailsWith<BackupFormatException> { codec.decode(tampered) }
    }

    // --- 未知の値（前方互換） ---

    @Test
    fun 知らない切り口の発話は落として読み進める() {
        // RemarkAngle を改名した後の古いzip。落ちるのは「もう言った」の抑止だけ
        val tampered = encoded().map { entry ->
            if (entry.name == "utterance_logs.json") {
                BackupEntry(
                    entry.name,
                    """[{"sessionId":1,"placeRef":"way:42","angle":"NOPE","saidAt":1}]"""
                        .encodeToByteArray(),
                )
            } else {
                entry
            }
        }

        assertTrue(codec.decode(tampered).utterances.isEmpty())
    }

    @Test
    fun 知らない終わり方は不明として読む() {
        val tampered = encoded().map { entry ->
            if (entry.name == "walk_sessions.json") {
                BackupEntry(
                    entry.name,
                    (
                        """[{"id":1,"startedAt":1,"endedAt":2,"endReason":"NOPE"},""" +
                            """{"id":2,"startedAt":3,"endedAt":null,"endReason":null}]"""
                        ).encodeToByteArray(),
                )
            } else {
                entry
            }
        }

        assertEquals(listOf(null, null), codec.decode(tampered).sessions.map { it.endReason })
    }

    private fun counts(sampleCount: Int = data.samples.size): String = """
        {"sessionCount":${data.sessions.size},"sampleCount":$sampleCount,
        "passageCount":${data.passages.size},"stepImportCount":${data.stepImports.size},
        "sessionWeatherCount":${data.sessionWeathers.size},
        "utteranceCount":${data.utterances.size},
        "llmCacheCount":${data.llmCacheEntries.size},
        "snapshotCount":${data.snapshots.size}}
    """.trimIndent().replace("\n", "")

    private fun replaceManifest(entries: List<BackupEntry>, manifestJson: String) =
        entries.map { entry ->
            if (entry.name == "manifest.json") {
                BackupEntry(entry.name, manifestJson.encodeToByteArray())
            } else {
                entry
            }
        }
}

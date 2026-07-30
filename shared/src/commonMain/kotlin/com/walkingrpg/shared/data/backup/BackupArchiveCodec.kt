package com.walkingrpg.shared.data.backup

import com.walkingrpg.shared.data.snapshot.MonthlySnapshotStatsCodec
import com.walkingrpg.shared.domain.backup.BackupCodec
import com.walkingrpg.shared.domain.backup.BackupCounts
import com.walkingrpg.shared.domain.backup.BackupData
import com.walkingrpg.shared.domain.backup.BackupFormatException
import com.walkingrpg.shared.domain.backup.BackupSnapshotImage
import com.walkingrpg.shared.domain.llm.LlmCacheEntry
import com.walkingrpg.shared.domain.llm.LlmTaskKind
import com.walkingrpg.shared.domain.llm.RemarkAngle
import com.walkingrpg.shared.domain.llm.UtteranceRecord
import com.walkingrpg.shared.domain.matching.Passage
import com.walkingrpg.shared.domain.snapshot.CalendarMonth
import com.walkingrpg.shared.domain.snapshot.Snapshot
import com.walkingrpg.shared.domain.steps.CalendarDay
import com.walkingrpg.shared.domain.steps.StepImport
import com.walkingrpg.shared.domain.walk.LocationSample
import com.walkingrpg.shared.domain.walk.SessionEndReason
import com.walkingrpg.shared.domain.walk.WalkSession
import com.walkingrpg.shared.domain.weather.SessionWeather
import com.walkingrpg.shared.domain.weather.WeatherCondition
import com.walkingrpg.shared.platform.BackupEntry
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * [BackupCodec] の実装。zipの中身の形（＝バックアップの契約）を持つ。
 *
 * ```
 * manifest.json          スキーマ版・作成時刻・各表の件数
 * walk_sessions.json     真実の源（**IDを保持する**）
 * location_samples.json  真実の源（実座標。自宅を含む）
 * passages.json          真実の源に準ずる（再マッチせずそのまま戻す）
 * step_imports.json      押し忘れ救済の観測値
 * session_weathers.json  外部APIの観測値（二度と取れない）
 * utterance_logs.json    再計算できない追記ログ（idは持たない）
 * llm_cache.json         導出キャッシュだが再課金を避けるため入れる
 * snapshots.json         アルバムの行
 * snapshots/<month>.png  アルバムの画像（`snapshot.image_path` と同じ相対パス）
 * ```
 *
 * シリアライズをデータ層に置くのは既存の流儀どおり（`WalkSessionExporterImpl` の
 * `SessionExportPayload` / [MonthlySnapshotStatsCodec] が先例）：ドメインは外部依存なしの
 * 純Kotlin（architecture.md §3）なので `@Serializable` はドメインモデルには付けない。
 *
 * ## キー名と [SCHEMA_VERSION]
 * 一度出したキー名は変えない（古いzipが読めなくなる）。項目を足すときは
 * `= 既定値` 付きの省略可能な項目にして [SCHEMA_VERSION] を上げる。
 * 読み込みは [Json.ignoreUnknownKeys] なので、新しいアプリで作ったzipを
 * 古いアプリが（知っている項目だけで）開ける。
 */
internal class BackupArchiveCodec : BackupCodec {

    /**
     * `prettyPrint` は付けない：`location_samples.json` は1回の散歩で数百〜千行になり、
     * 整形すると容量が数倍に膨らむ（読むのは人間ではなくこのクラス）。
     */
    private val json = Json { ignoreUnknownKeys = true }

    override fun encode(data: BackupData, exportedAtMs: Long): List<BackupEntry> = buildList {
        add(
            textEntry(
                MANIFEST,
                json.encodeToString(
                    ManifestPayload(
                        exportedAtMs = exportedAtMs,
                        counts = data.counts.toPayload(),
                    ),
                ),
            ),
        )
        add(
            textEntry(
                WALK_SESSIONS,
                json.encodeToString(
                    data.sessions.map {
                        SessionPayload(
                            id = it.id,
                            startedAt = it.startedAtMs,
                            endedAt = it.endedAtMs,
                            endReason = it.endReason?.name,
                        )
                    },
                ),
            ),
        )
        add(
            textEntry(
                LOCATION_SAMPLES,
                json.encodeToString(
                    data.samples.map {
                        SamplePayload(
                            sessionId = it.sessionId,
                            ts = it.timestampMs,
                            lat = it.latitude,
                            lon = it.longitude,
                            accuracy = it.accuracyMeters,
                        )
                    },
                ),
            ),
        )
        add(
            textEntry(
                PASSAGES,
                json.encodeToString(
                    data.passages.map {
                        PassagePayload(sessionId = it.sessionId, wayId = it.wayId, ts = it.timestampMs)
                    },
                ),
            ),
        )
        add(
            textEntry(
                STEP_IMPORTS,
                json.encodeToString(
                    data.stepImports.map {
                        StepImportPayload(
                            date = it.day.iso,
                            steps = it.steps,
                            distanceEstimate = it.distanceEstimateMeters,
                        )
                    },
                ),
            ),
        )
        add(
            textEntry(
                SESSION_WEATHERS,
                json.encodeToString(
                    data.sessionWeathers.map {
                        SessionWeatherPayload(
                            sessionId = it.sessionId,
                            condition = it.condition.name,
                            temperature = it.temperatureCelsius,
                            fetchedAt = it.fetchedAtMs,
                        )
                    },
                ),
            ),
        )
        add(
            textEntry(
                UTTERANCE_LOGS,
                json.encodeToString(
                    data.utterances.map {
                        UtterancePayload(
                            sessionId = it.sessionId,
                            placeRef = it.placeRef,
                            angle = it.angle.name,
                            saidAt = it.saidAtMs,
                        )
                    },
                ),
            ),
        )
        add(
            textEntry(
                LLM_CACHE,
                json.encodeToString(
                    data.llmCacheEntries.map {
                        LlmCachePayload(
                            cacheKey = it.cacheKey,
                            kind = it.kind.name,
                            promptHash = it.promptHash,
                            text = it.text,
                            createdAt = it.createdAtMs,
                        )
                    },
                ),
            ),
        )
        add(
            textEntry(
                SNAPSHOTS,
                json.encodeToString(
                    data.snapshots.map {
                        SnapshotPayload(
                            month = it.month.iso,
                            imagePath = it.imagePath,
                            // `stats_json` の形の契約は MonthlySnapshotStatsCodec が持つ。
                            // ここで別の形に詰め替えると、契約が2箇所に増える。
                            statsJson = MonthlySnapshotStatsCodec.encode(it.stats),
                            createdAt = it.createdAtMs,
                        )
                    },
                ),
            ),
        )
        // 画像はエントリ名＝相対パスそのまま（復元先を計算し直さない）
        data.snapshotImages.forEach { image ->
            add(BackupEntry(name = image.relativePath, bytes = image.bytes))
        }
    }

    override fun decode(entries: List<BackupEntry>): BackupData {
        val byName = entries.associateBy { it.name }

        val manifest = decodeEntry<ManifestPayload>(byName, MANIFEST)
        if (manifest.schemaVersion != SCHEMA_VERSION) {
            throw BackupFormatException(
                "対応していないバックアップ形式です（このアプリは版 $SCHEMA_VERSION、" +
                    "ファイルは版 ${manifest.schemaVersion}）。",
            )
        }

        val sessions = decodeEntry<List<SessionPayload>>(byName, WALK_SESSIONS)
        val samples = decodeEntry<List<SamplePayload>>(byName, LOCATION_SAMPLES)
        val passages = decodeEntry<List<PassagePayload>>(byName, PASSAGES)
        val stepImports = decodeEntry<List<StepImportPayload>>(byName, STEP_IMPORTS)
        val weathers = decodeEntry<List<SessionWeatherPayload>>(byName, SESSION_WEATHERS)
        val utterances = decodeEntry<List<UtterancePayload>>(byName, UTTERANCE_LOGS)
        val llmCache = decodeEntry<List<LlmCachePayload>>(byName, LLM_CACHE)
        val snapshots = decodeEntry<List<SnapshotPayload>>(byName, SNAPSHOTS)

        // 件数の照合は「JSONの行数」に対して行う（この後の enum の読み替えで
        // 落ちる行があっても、ファイルが壊れていないことは確かめられる）。
        val counts = manifest.counts
        verifyCount("散歩", counts.sessionCount, sessions.size)
        verifyCount("測位サンプル", counts.sampleCount, samples.size)
        verifyCount("通過", counts.passageCount, passages.size)
        verifyCount("歩数の取り込み", counts.stepImportCount, stepImports.size)
        verifyCount("天候", counts.sessionWeatherCount, weathers.size)
        verifyCount("パートナーの発話", counts.utteranceCount, utterances.size)
        verifyCount("生成キャッシュ", counts.llmCacheCount, llmCache.size)
        verifyCount("スナップショット", counts.snapshotCount, snapshots.size)

        return runCatching {
            BackupData(
                sessions = sessions.map { row ->
                    WalkSession(
                        id = row.id,
                        startedAtMs = row.startedAt,
                        endedAtMs = row.endedAt,
                        // 知らない終わり方は「不明」に落とす（WalkSessionRepositoryImpl と同じ）
                        endReason = row.endReason?.let { name ->
                            SessionEndReason.entries.firstOrNull { it.name == name }
                        },
                    )
                },
                samples = samples.map { row ->
                    LocationSample(
                        sessionId = row.sessionId,
                        timestampMs = row.ts,
                        latitude = row.lat,
                        longitude = row.lon,
                        accuracyMeters = row.accuracy,
                    )
                },
                passages = passages.map { row ->
                    Passage(sessionId = row.sessionId, wayId = row.wayId, timestampMs = row.ts)
                },
                stepImports = stepImports.map { row ->
                    StepImport(
                        day = CalendarDay(row.date),
                        steps = row.steps,
                        distanceEstimateMeters = row.distanceEstimate,
                    )
                },
                sessionWeathers = weathers.map { row ->
                    SessionWeather(
                        sessionId = row.sessionId,
                        // 知らない天候は UNKNOWN（SessionWeatherRepositoryImpl と同じ）
                        condition = WeatherCondition.entries.firstOrNull { it.name == row.condition }
                            ?: WeatherCondition.UNKNOWN,
                        temperatureCelsius = row.temperature,
                        fetchedAtMs = row.fetchedAt,
                    )
                },
                // 知らない切り口・種別の行は落とす（UtteranceLogRepositoryImpl /
                // LlmCacheRepositoryImpl と同じ。落ちても失われるのは抑止とキャッシュだけ）
                utterances = utterances.mapNotNull { row ->
                    val angle = RemarkAngle.entries.firstOrNull { it.name == row.angle }
                        ?: return@mapNotNull null
                    UtteranceRecord(
                        sessionId = row.sessionId,
                        placeRef = row.placeRef,
                        angle = angle,
                        saidAtMs = row.saidAt,
                    )
                },
                llmCacheEntries = llmCache.mapNotNull { row ->
                    val kind = LlmTaskKind.entries.firstOrNull { it.name == row.kind }
                        ?: return@mapNotNull null
                    LlmCacheEntry(
                        cacheKey = row.cacheKey,
                        kind = kind,
                        promptHash = row.promptHash,
                        text = row.text,
                        createdAtMs = row.createdAt,
                    )
                },
                snapshots = snapshots.map { row ->
                    Snapshot(
                        month = CalendarMonth(row.month),
                        imagePath = row.imagePath,
                        stats = MonthlySnapshotStatsCodec.decode(row.statsJson),
                        createdAtMs = row.createdAt,
                    )
                },
                // 行のある月には必ず画像が要る（行だけ残った「アルバム割れ」を
                // インポートで作らない。`Snapshot.sq`）。
                snapshotImages = snapshots.map { row ->
                    // zipは信頼できない入力。`image_path` はそのままファイル書き込みの
                    // パスになるので、正規の形（snapshots/YYYY-MM.png）以外は
                    // ここで弾く（`../` などによるパストラバーサル防止）。
                    if (!SAFE_IMAGE_PATH.matches(row.imagePath)) {
                        throw BackupFormatException(
                            "不正な画像パスです: ${row.imagePath}",
                        )
                    }
                    val entry = byName[row.imagePath]
                        ?: throw BackupFormatException(
                            "スナップショット画像が入っていません: ${row.imagePath}",
                        )
                    BackupSnapshotImage(relativePath = row.imagePath, bytes = entry.bytes)
                },
            )
        }.getOrElse { error ->
            // ドメインモデルの require（月の形式・件数の下限など）で弾かれた場合も
            // 「ファイルの形が違う」として扱う＝DBには何も書かれない。
            if (error is BackupFormatException) throw error
            throw BackupFormatException(
                "バックアップの中身が壊れています: ${error.message ?: "原因不明"}",
            )
        }
    }

    private fun textEntry(name: String, text: String): BackupEntry =
        BackupEntry(name = name, bytes = text.encodeToByteArray())

    /**
     * 必須エントリを1つ読む。無ければ・読めなければ [BackupFormatException]。
     *
     * 「無い＝0件」として通さないのは、エントリの欠落が
     * 「そのテーブルが空だったzip」と区別できないと、壊れたファイルで
     * 全削除が走ってしまうため。
     */
    private inline fun <reified T> decodeEntry(
        byName: Map<String, BackupEntry>,
        name: String,
    ): T {
        val entry = byName[name]
            ?: throw BackupFormatException("バックアップに $name が入っていません。")
        return try {
            json.decodeFromString<T>(entry.bytes.decodeToString())
        } catch (error: Exception) {
            throw BackupFormatException("$name を読めませんでした: ${error.message ?: "形式が不正"}")
        }
    }

    private fun verifyCount(label: String, expected: Int, actual: Int) {
        if (expected != actual) {
            throw BackupFormatException(
                "$label の件数が合いません（manifestは${expected}件、中身は${actual}件）。",
            )
        }
    }

    internal companion object {
        /**
         * 今の形のバージョン。項目・エントリを足したら上げる（消す・意味を変えるのは禁止）。
         * シナリオ進行を実装したらここを上げてエントリを足す（[BackupCodec] のKDoc）。
         */
        const val SCHEMA_VERSION: Int = 1

        /**
         * インポートで受け入れる画像パスの正規形。`snapshotImagePath()` が生成する形と
         * 1対1で、これ以外（絶対パス・`..`・別ディレクトリ）は改ざんされたzipとして弾く。
         */
        val SAFE_IMAGE_PATH = Regex("""snapshots/\d{4}-\d{2}\.png""")

        const val MANIFEST = "manifest.json"
        const val WALK_SESSIONS = "walk_sessions.json"
        const val LOCATION_SAMPLES = "location_samples.json"
        const val PASSAGES = "passages.json"
        const val STEP_IMPORTS = "step_imports.json"
        const val SESSION_WEATHERS = "session_weathers.json"
        const val UTTERANCE_LOGS = "utterance_logs.json"
        const val LLM_CACHE = "llm_cache.json"
        const val SNAPSHOTS = "snapshots.json"
    }
}

private fun BackupCounts.toPayload(): CountsPayload = CountsPayload(
    sessionCount = sessionCount,
    sampleCount = sampleCount,
    passageCount = passageCount,
    stepImportCount = stepImportCount,
    sessionWeatherCount = sessionWeatherCount,
    utteranceCount = utteranceCount,
    llmCacheCount = llmCacheCount,
    snapshotCount = snapshotCount,
)

/**
 * `manifest.json` の形。
 *
 * 件数を持たせるのは**インポート前の検証のため**：JSONの配列が途中で切れた
 * （転送が失敗した・エディタで開いて壊した）zipを、書き込む前に弾ける。
 */
@Serializable
private data class ManifestPayload(
    val schemaVersion: Int = BackupArchiveCodec.SCHEMA_VERSION,
    val exportedAtMs: Long,
    val counts: CountsPayload,
)

@Serializable
private data class CountsPayload(
    val sessionCount: Int,
    val sampleCount: Int,
    val passageCount: Int,
    val stepImportCount: Int,
    val sessionWeatherCount: Int,
    val utteranceCount: Int,
    val llmCacheCount: Int,
    val snapshotCount: Int,
)

/** `id` を持つのがこの表だけの特徴（他の表がこのIDで自分の行を指す）。 */
@Serializable
private data class SessionPayload(
    val id: Long,
    val startedAt: Long,
    val endedAt: Long?,
    val endReason: String?,
)

@Serializable
private data class SamplePayload(
    val sessionId: Long,
    val ts: Long,
    val lat: Double,
    val lon: Double,
    val accuracy: Double,
)

@Serializable
private data class PassagePayload(
    val sessionId: Long,
    val wayId: Long,
    val ts: Long,
)

@Serializable
private data class StepImportPayload(
    val date: String,
    val steps: Int,
    val distanceEstimate: Double?,
)

@Serializable
private data class SessionWeatherPayload(
    val sessionId: Long,
    val condition: String,
    val temperature: Double?,
    val fetchedAt: Long,
)

/** `id`（AUTOINCREMENTの採番）は持たない：外から参照されないので運ぶ意味がない。 */
@Serializable
private data class UtterancePayload(
    val sessionId: Long,
    val placeRef: String,
    val angle: String,
    val saidAt: Long,
)

@Serializable
private data class LlmCachePayload(
    val cacheKey: String,
    val kind: String,
    val promptHash: String,
    val text: String,
    val createdAt: Long,
)

/** `statsJson` は `snapshot.stats_json` の中身そのまま（形の契約は変えない）。 */
@Serializable
private data class SnapshotPayload(
    val month: String,
    val imagePath: String,
    val statsJson: String,
    val createdAt: Long,
)

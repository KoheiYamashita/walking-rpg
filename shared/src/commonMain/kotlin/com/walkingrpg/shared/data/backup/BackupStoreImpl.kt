package com.walkingrpg.shared.data.backup

import com.walkingrpg.shared.data.db.WalkingRpgDatabase
import com.walkingrpg.shared.data.snapshot.MonthlySnapshotStatsCodec
import com.walkingrpg.shared.domain.backup.BackupData
import com.walkingrpg.shared.domain.backup.BackupSnapshotImage
import com.walkingrpg.shared.domain.backup.BackupStore
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
import com.walkingrpg.shared.platform.SnapshotImageStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [BackupStore] のSQLDelight実装。
 *
 * ドメインモデル⇄DBスキーマの変換はこのクラスに閉じる（architecture.md §2）。
 * バックアップ対象の表を横断するので、表ごとのRepositoryではなくDBを直接持つ
 * （理由は [BackupStore] のKDoc「跨いだ原子性」）。
 */
internal class BackupStoreImpl(
    private val database: WalkingRpgDatabase,
    private val snapshotImageStore: SnapshotImageStore,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : BackupStore {

    override suspend fun read(): BackupData = withContext(dispatcher) {
        val snapshots = database.snapshotQueries.selectAllSnapshots().executeAsList().map { row ->
            Snapshot(
                month = CalendarMonth(row.month),
                imagePath = row.image_path,
                stats = MonthlySnapshotStatsCodec.decode(row.stats_json),
                createdAtMs = row.created_at,
            )
        }
        // 画像の無い月（＝行だけ残った「アルバム割れ」）はエクスポートに乗せない。
        // 乗せると「行はあるが画像が無いzip」ができ、インポート側の検証で弾かれる
        // ＝自分が書き出したファイルを自分で読めなくなる。
        // 書き込み順が「画像 → 行」（GenerateMonthlySnapshotsUseCase）なので、
        // 通常この状態にはならない。
        val images = snapshots.mapNotNull { snapshot ->
            snapshotImageStore.read(snapshot.imagePath)?.let { bytes ->
                BackupSnapshotImage(relativePath = snapshot.imagePath, bytes = bytes)
            }
        }
        val exportablePaths = images.map { it.relativePath }.toSet()

        BackupData(
            sessions = database.walkSessionQueries.selectAllSessions().executeAsList().map { row ->
                WalkSession(
                    id = row.id,
                    startedAtMs = row.started_at,
                    endedAtMs = row.ended_at,
                    endReason = row.end_reason?.let { name ->
                        SessionEndReason.entries.firstOrNull { it.name == name }
                    },
                )
            },
            samples = database.locationSampleQueries.selectAllSamples().executeAsList().map { row ->
                LocationSample(
                    sessionId = row.session_id,
                    timestampMs = row.ts,
                    latitude = row.lat,
                    longitude = row.lon,
                    accuracyMeters = row.accuracy,
                )
            },
            passages = database.passageQueries.selectAllPassages().executeAsList().map { row ->
                Passage(sessionId = row.session_id, wayId = row.way_id, timestampMs = row.ts)
            },
            stepImports = database.stepImportQueries.selectAllStepImports().executeAsList()
                .map { row ->
                    StepImport(
                        day = CalendarDay(row.date),
                        steps = row.steps.toInt(),
                        distanceEstimateMeters = row.distance_estimate,
                    )
                },
            sessionWeathers = database.sessionWeatherQueries.selectAllSessionWeathers()
                .executeAsList()
                .map { row ->
                    SessionWeather(
                        sessionId = row.session_id,
                        condition = WeatherCondition.entries.firstOrNull { it.name == row.condition }
                            ?: WeatherCondition.UNKNOWN,
                        temperatureCelsius = row.temperature,
                        fetchedAtMs = row.fetched_at,
                    )
                },
            utterances = database.utteranceLogQueries.selectAllUtterances().executeAsList()
                .mapNotNull { row ->
                    val angle = RemarkAngle.entries.firstOrNull { it.name == row.angle }
                        ?: return@mapNotNull null
                    UtteranceRecord(
                        sessionId = row.session_id,
                        placeRef = row.place_ref,
                        angle = angle,
                        saidAtMs = row.said_at,
                    )
                },
            llmCacheEntries = database.llmCacheQueries.selectAllLlmCache().executeAsList()
                .mapNotNull { row ->
                    val kind = LlmTaskKind.entries.firstOrNull { it.name == row.kind }
                        ?: return@mapNotNull null
                    LlmCacheEntry(
                        cacheKey = row.cache_key,
                        kind = kind,
                        promptHash = row.prompt_hash,
                        text = row.text,
                        createdAtMs = row.created_at,
                    )
                },
            snapshots = snapshots.filter { it.imagePath in exportablePaths },
            snapshotImages = images,
        )
    }

    /**
     * 順序に意味がある：
     *
     * 1. **消える画像のパスを控える**（行を消したあとでは、どれが使われていたか分からない）
     * 2. **新しい画像を書く**（DBの行より先。`Snapshot.sq`「行だけ残して画像が無い状態を作らない」）
     * 3. **DBの行を1トランザクションで入れ替える**（途中の状態を残さない）
     * 4. **使われなくなった画像を消す**（行が消えたことが確定してから
     *    ＝どの行からも参照されていない画像だけを消す）
     *
     * 3で落ちた場合、DBは前の状態のまま／画像は新しいものが増えている状態になる。
     * 前の行が指す画像は消していないので**アルバムは割れない**（余分なファイルが
     * 残るだけで、次のインポートで整理される）。
     */
    override suspend fun replaceAll(data: BackupData): Unit = withContext(dispatcher) {
        val incomingPaths = data.snapshotImages.map { it.relativePath }.toSet()
        val obsoletePaths = database.snapshotQueries.selectAllSnapshots().executeAsList()
            .map { it.image_path }
            .toSet() - incomingPaths

        data.snapshotImages.forEach { image ->
            snapshotImageStore.write(image.relativePath, image.bytes)
        }

        val sessions = database.walkSessionQueries
        val samples = database.locationSampleQueries
        val passages = database.passageQueries
        val stepImports = database.stepImportQueries
        val weathers = database.sessionWeatherQueries
        val utterances = database.utteranceLogQueries
        val llmCache = database.llmCacheQueries
        val snapshots = database.snapshotQueries

        database.transaction {
            // 削除は「参照する側」から。walk_session を最後に消す
            // （location_sample / passage / session_weather が外部キーで指している）
            llmCache.deleteAllLlmCache()
            utterances.deleteAllUtterances()
            snapshots.deleteAllSnapshots()
            stepImports.deleteAllStepImports()
            weathers.deleteAllSessionWeathers()
            passages.deleteAllPassages()
            samples.deleteAllSamples()
            sessions.deleteAllSessions()

            // 挿入は逆に「参照される側」から。IDを保つのでこの順でなければ入らない
            data.sessions.forEach { session ->
                sessions.insertSessionWithId(
                    id = session.id,
                    started_at = session.startedAtMs,
                    ended_at = session.endedAtMs,
                    end_reason = session.endReason?.name,
                )
            }
            data.samples.forEach { sample ->
                samples.insertSample(
                    session_id = sample.sessionId,
                    ts = sample.timestampMs,
                    lat = sample.latitude,
                    lon = sample.longitude,
                    accuracy = sample.accuracyMeters,
                )
            }
            data.passages.forEach { passage ->
                passages.insertPassage(
                    session_id = passage.sessionId,
                    way_id = passage.wayId,
                    ts = passage.timestampMs,
                )
            }
            data.sessionWeathers.forEach { weather ->
                weathers.upsertSessionWeather(
                    session_id = weather.sessionId,
                    condition = weather.condition.name,
                    temperature = weather.temperatureCelsius,
                    fetched_at = weather.fetchedAtMs,
                )
            }
            data.stepImports.forEach { stepImport ->
                stepImports.upsertStepImport(
                    date = stepImport.day.iso,
                    steps = stepImport.steps.toLong(),
                    distance_estimate = stepImport.distanceEstimateMeters,
                )
            }
            // id は復元しない（AUTOINCREMENTの採番でしかない。`UtteranceLog.sq`）
            data.utterances.forEach { record ->
                utterances.insertUtterance(
                    session_id = record.sessionId,
                    place_ref = record.placeRef,
                    angle = record.angle.name,
                    said_at = record.saidAtMs,
                )
            }
            data.llmCacheEntries.forEach { entry ->
                llmCache.upsertLlmCache(
                    cache_key = entry.cacheKey,
                    kind = entry.kind.name,
                    prompt_hash = entry.promptHash,
                    text = entry.text,
                    created_at = entry.createdAtMs,
                )
            }
            // replaceSnapshot（OR REPLACE）を使う唯一の場所。理由は `Snapshot.sq`
            data.snapshots.forEach { snapshot ->
                snapshots.replaceSnapshot(
                    month = snapshot.month.iso,
                    image_path = snapshot.imagePath,
                    stats_json = MonthlySnapshotStatsCodec.encode(snapshot.stats),
                    created_at = snapshot.createdAtMs,
                )
            }
        }

        obsoletePaths.forEach { path -> snapshotImageStore.delete(path) }
    }
}

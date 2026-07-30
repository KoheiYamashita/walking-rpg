package com.walkingrpg.shared.data.snapshot

import com.walkingrpg.shared.domain.snapshot.MonthlySnapshotStats
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * `snapshot.stats_json` の形（読み書き両方の契約）。
 *
 * シリアライズをデータ層に置くのは既存の流儀どおり（`WalkSessionExporterImpl` の
 * `SessionExportPayload` が先例）：ドメインは外部依存なしの純Kotlin
 * （architecture.md §3）なので、`@Serializable` はドメインモデルには付けない。
 *
 * ## schemaVersion を持つ理由
 * この列は**作り直せない**（`Snapshot.sq`）。値を足したい将来の自分が
 * 「古い行に無い項目」を見分けられなければ、古いアルバムを読めなくなる。
 * 足すときは `= 既定値` 付きの省略可能な項目にして [SCHEMA_VERSION] を上げる
 * ＝古い行は既定値で読めたまま、新しい行だけ中身が濃くなる。
 */
internal object MonthlySnapshotStatsCodec {

    /** 今の形のバージョン。項目を足したら上げる（消す・意味を変えるのは禁止）。 */
    const val SCHEMA_VERSION: Int = 1

    /**
     * 保存する形。読めない項目は無視する（前方互換）：新しいバージョンのアプリで作った行を
     * 古いバージョンが読んでも、知っている項目だけで開ける方が「消さない」に近い。
     */
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(stats: MonthlySnapshotStats): String = json.encodeToString(
        MonthlySnapshotStatsPayload(
            distanceMeters = stats.distanceMeters,
            newWayCount = stats.newWayCount,
            discoveredSpeciesNames = stats.discoveredSpeciesNames,
            sessionCount = stats.sessionCount,
        ),
    )

    fun decode(text: String): MonthlySnapshotStats {
        val payload = json.decodeFromString<MonthlySnapshotStatsPayload>(text)
        return MonthlySnapshotStats(
            distanceMeters = payload.distanceMeters,
            newWayCount = payload.newWayCount,
            discoveredSpeciesNames = payload.discoveredSpeciesNames,
            sessionCount = payload.sessionCount,
        )
    }
}

/**
 * `stats_json` の1件。項目名は短く固定する（`snapshot` の行は書き換えないので、
 * **一度出したキー名は変えない**）。
 */
@Serializable
private data class MonthlySnapshotStatsPayload(
    val schemaVersion: Int = MonthlySnapshotStatsCodec.SCHEMA_VERSION,
    val distanceMeters: Double,
    val newWayCount: Int,
    val discoveredSpeciesNames: List<String>,
    val sessionCount: Int,
)

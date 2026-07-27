package com.walkingrpg.shared.data

import com.walkingrpg.shared.domain.walk.WalkSessionExporter
import com.walkingrpg.shared.domain.walk.WalkSessionRepository
import com.walkingrpg.shared.platform.FileShare
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * [WalkSessionExporter] の実装。セッションのサンプルをJSONにして共有UIへ渡す。
 *
 * **このJSONには実際の歩行位置（＝自宅を含む）が入る。**
 * 出力先はユーザーが選ぶ共有先だけで、リポジトリへ自動で持ち込む経路は用意しない。
 * テストフィクスチャ化は匿名化方針を決めてから（#8）。
 */
internal class WalkSessionExporterImpl(
    private val sessionRepository: WalkSessionRepository,
    private val fileShare: FileShare,
) : WalkSessionExporter {

    private val json = Json { prettyPrint = true }

    override suspend fun exportSession(sessionId: Long): String {
        val session = requireNotNull(sessionRepository.session(sessionId)) {
            "セッション($sessionId)が見つかりません"
        }
        val samples = sessionRepository.samples(sessionId)

        val payload = SessionExportPayload(
            sessionId = session.id,
            startedAt = session.startedAtMs,
            endedAt = session.endedAtMs,
            endReason = session.endReason?.name,
            sampleCount = samples.size,
            samples = samples.map {
                SampleExportPayload(
                    ts = it.timestampMs,
                    lat = it.latitude,
                    lon = it.longitude,
                    accuracy = it.accuracyMeters,
                )
            },
        )

        val fileName = "walk-session-${session.id}.json"
        fileShare.shareText(fileName, MIME_TYPE_JSON, json.encodeToString(payload))
        return fileName
    }

    private companion object {
        const val MIME_TYPE_JSON = "application/json"
    }
}

/** エクスポートJSONの形。読み込み側（#8のGPSリプレイ）が参照する契約。 */
@Serializable
private data class SessionExportPayload(
    val schemaVersion: Int = 1,
    val sessionId: Long,
    val startedAt: Long,
    val endedAt: Long?,
    val endReason: String?,
    val sampleCount: Int,
    val samples: List<SampleExportPayload>,
)

@Serializable
private data class SampleExportPayload(
    val ts: Long,
    val lat: Double,
    val lon: Double,
    val accuracy: Double,
)

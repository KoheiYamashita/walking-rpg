package com.walkingrpg.shared.domain.walk

/**
 * 散歩セッション（architecture.md §4 `walk_session`）。
 *
 * 純Kotlinのドメインモデル。DBスキーマとの変換はデータ層に閉じる。
 */
data class WalkSession(
    val id: Long,
    val startedAtMs: Long,
    val endedAtMs: Long? = null,
    val endReason: SessionEndReason? = null,
) {
    val isOpen: Boolean get() = endedAtMs == null

    /** セッションの長さ（記録中は [nowMs] までの経過）。 */
    fun durationMs(nowMs: Long): Long = ((endedAtMs ?: nowMs) - startedAtMs).coerceAtLeast(0L)
}

/** セッションの終わり方（design.md §3「セッションの区切り」）。 */
enum class SessionEndReason {
    /** 「散歩を終える」を押した。 */
    MANUAL,

    /** 自宅付近＋停止検知による自動終了（#4以降で実装）。 */
    AUTO_ARRIVAL,

    /** アプリが落ちるなどして畳まれた（記録は残るが完了扱いにしない）。 */
    ABANDONED,
}

/**
 * 永続化済みの測位サンプル（architecture.md §4 `location_sample`）。
 */
data class LocationSample(
    val sessionId: Long,
    val timestampMs: Long,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double,
)

/**
 * プラットフォームの測位APIから届いた生の1点。まだセッションに紐づいていない。
 */
data class LocationFix(
    val timestampMs: Long,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double,
) {
    fun toSample(sessionId: Long): LocationSample = LocationSample(
        sessionId = sessionId,
        timestampMs = timestampMs,
        latitude = latitude,
        longitude = longitude,
        accuracyMeters = accuracyMeters,
    )
}

/** セッション一覧に出す1行（セッション＋サンプル数）。 */
data class WalkSessionSummary(
    val session: WalkSession,
    val sampleCount: Int,
)

/** 測位が始められない／続けられないときに測位ストリームが投げる例外。 */
class LocationUnavailableException(
    override val message: String,
    override val cause: Throwable? = null,
) : Exception(message, cause)

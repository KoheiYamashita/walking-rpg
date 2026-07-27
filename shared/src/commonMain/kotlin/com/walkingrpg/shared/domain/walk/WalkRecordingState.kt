package com.walkingrpg.shared.domain.walk

/**
 * 記録中セッションの状態機械。純関数だけで構成し、ここにテストを集中させる
 * （architecture.md §7「ドメイン層は純Kotlinなので通常のunit testで全部書ける」）。
 *
 * 時刻は状態自身では取らず、遷移関数の引数として外から渡す。
 */
data class WalkRecordingState(
    val sessionId: Long? = null,
    val startedAtMs: Long? = null,
    val stoppedAtMs: Long? = null,
    val sampleCount: Int = 0,
    val lastSample: LocationSample? = null,
    val error: String? = null,
) {
    val isRecording: Boolean get() = sessionId != null

    /** 記録開始。前回の状態（エラー含む）は捨てる。 */
    fun started(sessionId: Long, startedAtMs: Long): WalkRecordingState =
        WalkRecordingState(sessionId = sessionId, startedAtMs = startedAtMs)

    /**
     * サンプルを1件記録した。
     * 記録中でない、または別セッションのサンプルは無視する（遅れて届いた分の混入防止）。
     */
    fun sampleRecorded(sample: LocationSample): WalkRecordingState =
        if (sample.sessionId != sessionId) {
            this
        } else {
            copy(sampleCount = sampleCount + 1, lastSample = sample, error = null)
        }

    /**
     * 記録停止。直近の実績（所要時間・サンプル数・最終サンプル）は表示のために残す。
     * 停止時刻を持たせて、停止後に経過時間が伸び続けないようにする。
     */
    fun stopped(stoppedAtMs: Long): WalkRecordingState =
        copy(sessionId = null, stoppedAtMs = stoppedAtMs)

    /** 測位エラー。記録自体は継続扱い（復帰したらサンプルが再び入る）。 */
    fun errored(message: String): WalkRecordingState = copy(error = message)

    /** 表示用スナップショット。経過時間などの「今」に依存する値をここで確定させる。 */
    fun snapshotAt(nowMs: Long): WalkRecordingSnapshot {
        val referenceMs = if (isRecording) nowMs else (stoppedAtMs ?: nowMs)
        return WalkRecordingSnapshot(
            isRecording = isRecording,
            sessionId = sessionId,
            elapsedMs = startedAtMs?.let { (referenceMs - it).coerceAtLeast(0L) } ?: 0L,
            sampleCount = sampleCount,
            lastAccuracyMeters = lastSample?.accuracyMeters,
            lastSampleAgeMs = lastSample?.let { (referenceMs - it.timestampMs).coerceAtLeast(0L) },
            error = error,
        )
    }
}

/**
 * 画面に出す計測値（スパイク用）。経過時間は算出済みで、UIは整形するだけ。
 */
data class WalkRecordingSnapshot(
    val isRecording: Boolean = false,
    val sessionId: Long? = null,
    val elapsedMs: Long = 0L,
    val sampleCount: Int = 0,
    val lastAccuracyMeters: Double? = null,
    val lastSampleAgeMs: Long? = null,
    val error: String? = null,
) {
    /**
     * 1秒間隔で来るはずのサンプルが何秒間途切れているか、から求めた
     * 「取れているはず／取れていない」の目安。欠落の体感確認用。
     */
    fun isStalled(thresholdMs: Long = STALL_THRESHOLD_MS): Boolean =
        isRecording && (lastSampleAgeMs == null || lastSampleAgeMs > thresholdMs)

    companion object {
        const val STALL_THRESHOLD_MS: Long = 10_000L
    }
}

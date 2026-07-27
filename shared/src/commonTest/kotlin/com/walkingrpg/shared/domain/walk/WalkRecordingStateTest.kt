package com.walkingrpg.shared.domain.walk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 記録中セッションの状態遷移（純Kotlin）。 */
class WalkRecordingStateTest {

    private fun sample(sessionId: Long, ts: Long, accuracy: Double = 5.0) = LocationSample(
        sessionId = sessionId,
        timestampMs = ts,
        latitude = 0.0,
        longitude = 0.0,
        accuracyMeters = accuracy,
    )

    @Test
    fun 初期状態は記録中ではない() {
        val state = WalkRecordingState()

        assertFalse(state.isRecording)
        assertEquals(0, state.sampleCount)
        assertNull(state.lastSample)
    }

    @Test
    fun 開始するとセッションIDと開始時刻が入る() {
        val state = WalkRecordingState().started(sessionId = 7L, startedAtMs = 1_000L)

        assertTrue(state.isRecording)
        assertEquals(7L, state.sessionId)
        assertEquals(1_000L, state.startedAtMs)
    }

    @Test
    fun 開始は前回のエラーと実績を引き継がない() {
        val previous = WalkRecordingState()
            .started(1L, 0L)
            .sampleRecorded(sample(1L, 100L))
            .errored("前回のエラー")
            .stopped(500L)

        val state = previous.started(sessionId = 2L, startedAtMs = 1_000L)

        assertEquals(0, state.sampleCount)
        assertNull(state.error)
        assertNull(state.lastSample)
        assertNull(state.stoppedAtMs)
    }

    @Test
    fun サンプルを記録するとカウントと最新サンプルが更新される() {
        val state = WalkRecordingState()
            .started(1L, 0L)
            .sampleRecorded(sample(1L, 100L, accuracy = 8.0))
            .sampleRecorded(sample(1L, 200L, accuracy = 4.0))

        assertEquals(2, state.sampleCount)
        assertEquals(200L, state.lastSample?.timestampMs)
        assertEquals(4.0, state.lastSample?.accuracyMeters)
    }

    @Test
    fun サンプル記録でエラー表示は解除される() {
        val state = WalkRecordingState()
            .started(1L, 0L)
            .errored("測位が途切れました")
            .sampleRecorded(sample(1L, 100L))

        assertNull(state.error)
    }

    @Test
    fun 記録中でなければサンプルは無視される() {
        val state = WalkRecordingState().sampleRecorded(sample(1L, 100L))

        assertEquals(0, state.sampleCount)
        assertNull(state.lastSample)
    }

    @Test
    fun 別セッションのサンプルは無視される() {
        val state = WalkRecordingState()
            .started(1L, 0L)
            .sampleRecorded(sample(sessionId = 99L, ts = 100L))

        assertEquals(0, state.sampleCount)
    }

    @Test
    fun 停止すると記録中ではなくなるが実績は残る() {
        val state = WalkRecordingState()
            .started(1L, 0L)
            .sampleRecorded(sample(1L, 100L))
            .stopped(stoppedAtMs = 300L)

        assertFalse(state.isRecording)
        assertEquals(1, state.sampleCount)
        assertEquals(100L, state.lastSample?.timestampMs)
    }

    @Test
    fun 記録中の経過時間は現在時刻で伸びる() {
        val state = WalkRecordingState().started(1L, startedAtMs = 1_000L)

        assertEquals(0L, state.snapshotAt(1_000L).elapsedMs)
        assertEquals(30_000L, state.snapshotAt(31_000L).elapsedMs)
    }

    @Test
    fun 停止後の経過時間は伸びない() {
        val state = WalkRecordingState()
            .started(1L, startedAtMs = 1_000L)
            .stopped(stoppedAtMs = 61_000L)

        assertEquals(60_000L, state.snapshotAt(61_000L).elapsedMs)
        assertEquals(60_000L, state.snapshotAt(999_000L).elapsedMs)
    }

    @Test
    fun スナップショットは最新精度と最終取得からの経過を持つ() {
        val snapshot = WalkRecordingState()
            .started(1L, 0L)
            .sampleRecorded(sample(1L, ts = 10_000L, accuracy = 6.5))
            .snapshotAt(12_000L)

        assertEquals(6.5, snapshot.lastAccuracyMeters)
        assertEquals(2_000L, snapshot.lastSampleAgeMs)
    }

    @Test
    fun サンプルが途切れると停滞と判定される() {
        val state = WalkRecordingState()
            .started(1L, 0L)
            .sampleRecorded(sample(1L, ts = 10_000L))

        assertFalse(state.snapshotAt(12_000L).isStalled())
        assertTrue(state.snapshotAt(30_000L).isStalled())
    }

    @Test
    fun まだ1件も取れていない記録中は停滞と判定される() {
        val snapshot = WalkRecordingState().started(1L, 0L).snapshotAt(1_000L)

        assertTrue(snapshot.isStalled())
    }

    @Test
    fun 停止中は停滞と判定しない() {
        val snapshot = WalkRecordingState().snapshotAt(1_000L)

        assertFalse(snapshot.isStalled())
    }
}

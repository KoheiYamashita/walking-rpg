package com.walkingrpg.shared.domain.feedback

import com.walkingrpg.shared.domain.walk.LocationSample

/**
 * 記録中セッションに歩行中フィードバックを結線するための口
 * （architecture.md §5「散歩セッション中」の判定〜振動）。
 *
 * `WalkRecorder` の実装はサンプルをここへ流すだけで、
 * 何を検知して何を鳴らすかは実装側（`WalkFeedbackImpl`）に閉じる。
 * `HomeArrivalDetector` を記録本体から切り離してあるのと同じ理由：
 * 記録（＝真実の源の書き込み）に、演出の都合を混ぜない。
 *
 * ## 実装の契約
 *
 * - **例外を投げない**。ここで落ちると散歩の記録そのものが止まる。
 *   フィードバックは無くても記録の正しさに影響しない（`WalkNotifier` と同じ方針）。
 * - **重い処理をしない**。呼ばれるのは測位サンプル1件ごと（1〜3秒間隔）で、
 *   ここで待つと `location_sample` の追記が遅れる。
 * - 呼び出しは記録の収集コルーチン1本からで、並行して呼ばれることはない。
 */
interface WalkFeedback {

    /**
     * 散歩が始まった。判定の土台（wayマスタ・現在の通過回数・振動の残数）を作り直す。
     *
     * 終了側の通知が無いのは、次の [walkStarted] が状態を丸ごと作り直すから。
     * 記録が畳まれる経路は4つ（手動・自宅到着・測位エラー・放置セッション）あり、
     * その全部に後始末を配ると、増えるたびに書き漏らす。
     */
    suspend fun walkStarted(sessionId: Long)

    /** 測位サンプルが1件記録された。判定を1段だけ進める。 */
    suspend fun sampleRecorded(sample: LocationSample)
}

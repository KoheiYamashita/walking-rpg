package com.walkingrpg.shared.platform

/**
 * 散歩まわりのOS通知（architecture.md §5）。
 *
 * 記録中の常駐通知（[SessionKeeper] = Foreground Service）とは役割が別なので分けてある：
 * あちらは「プロセスを殺されないための常駐」、こちらは「ユーザーに読ませる1本」。
 *
 * 通知が出せなくても記録の正しさには影響しないので、実装は失敗しても投げない
 * （権限が拒否されている・チャネルが無効にされている、はどちらも通常運転）。
 */
interface WalkNotifier {

    /**
     * 自動終了したときの「おかえり」。押し忘れを構造的に消すための出口（design.md §3）。
     *
     * タップしたら**そのセッションの振り返りが開く**（design.md §3 の 17:08
     * 「『おかえり』通知。振り返りを開く」）。そのために [sessionId] を受け取って
     * 通知のタップ先に載せる。載せ方はプラットフォームごとに違う（Androidは
     * PendingIntent の extra → `MainActivity` → `RequestPendingReviewUseCase`）。
     *
     * @param sessionId 畳んだセッション。振り返りを開く先。
     * @param durationMs 畳んだセッションの長さ。本文に出す。
     */
    fun notifyHomecoming(sessionId: Long, durationMs: Long)
}

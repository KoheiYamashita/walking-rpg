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
     * @param durationMs 畳んだセッションの長さ。本文に出す。
     */
    fun notifyHomecoming(durationMs: Long)
}

package com.walkingrpg.shared.platform

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.util.Log

/**
 * 「おかえり」通知（design.md §3 の自動終了の出口）。
 *
 * チャネルは記録中の常駐通知（[WalkRecordingService]）と分ける。あちらは
 * IMPORTANCE_LOW で黙って居座る通知、こちらは音を出して読ませたい通知なので、
 * 同じチャネルに混ぜるとユーザーがどちらかを切ったときにもう一方も巻き添えになる。
 *
 * POST_NOTIFICATIONS が拒否されていると `notify` は黙って捨てられる。
 * 通知が出なくてもセッションは正しく畳まれているので、ここでは記録を止めずログに残すだけ
 * （[AndroidSessionKeeper] と同じ方針）。
 */
internal class AndroidWalkNotifier(
    private val context: Context,
) : WalkNotifier {

    override fun notifyHomecoming(durationMs: Long) {
        runCatching {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            ensureChannel(manager)
            manager.notify(NOTIFICATION_ID, buildNotification(durationMs))
        }.onFailure { Log.w(TAG, "「おかえり」通知を出せませんでした", it) }
    }

    private fun ensureChannel(manager: NotificationManager) {
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "おかえり",
                // 帰宅に気づかせるのが目的なので既定の重要度（音・通知領域に出る）。
                // HIGH（ヘッドアップ）は画面を占有するほどの用件ではない。
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "自宅に着いて散歩の記録を自動で終えたときのお知らせ"
            },
        )
    }

    private fun buildNotification(durationMs: Long): Notification =
        Notification.Builder(context, CHANNEL_ID)
            .setContentTitle("おかえりなさい")
            .setContentText("${formatDuration(durationMs)}の散歩を記録しました")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setAutoCancel(true)
            .apply { launchIntent()?.let { setContentIntent(it) } }
            .build()

    /** 「1時間5分」「25分」。秒までは出さない（散歩の長さに秒の情報量は無い）。 */
    private fun formatDuration(durationMs: Long): String {
        val totalMinutes = (durationMs / 60_000L).coerceAtLeast(0L)
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return if (hours > 0) "${hours}時間${minutes}分" else "${minutes}分"
    }

    private fun launchIntent(): PendingIntent? {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: return null
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private companion object {
        const val TAG = "AndroidWalkNotifier"
        const val CHANNEL_ID = "walk_homecoming"

        // WalkRecordingService の常駐通知（1001）とは別枠にする（上書きし合わない）
        const val NOTIFICATION_ID = 1002
    }
}

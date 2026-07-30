package com.walkingrpg.shared.platform

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
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

    override fun notifyHomecoming(sessionId: Long, durationMs: Long) {
        runCatching {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            ensureChannel(manager)
            manager.notify(NOTIFICATION_ID, buildNotification(sessionId, durationMs))
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

    private fun buildNotification(sessionId: Long, durationMs: Long): Notification =
        Notification.Builder(context, CHANNEL_ID)
            .setContentTitle("おかえりなさい")
            .setContentText("${formatDuration(durationMs)}の散歩を記録しました。振り返りを開けます")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setAutoCancel(true)
            .apply { launchIntent(sessionId)?.let { setContentIntent(it) } }
            .build()

    /** 「1時間5分」「25分」。秒までは出さない（散歩の長さに秒の情報量は無い）。 */
    private fun formatDuration(durationMs: Long): String {
        val totalMinutes = (durationMs / 60_000L).coerceAtLeast(0L)
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return if (hours > 0) "${hours}時間${minutes}分" else "${minutes}分"
    }

    /**
     * タップ先＝アプリのランチャー画面＋「このセッションの振り返りを開いて」の印。
     *
     * `FLAG_ACTIVITY_SINGLE_TOP` を足すのは、既に起動しているときに新しい
     * Activityを積まず `onNewIntent` で受け取るため（[WalkNotifierIntents]）。
     * requestCode にセッションIDを使うのは、連続した散歩で古い extra が
     * 使い回されないようにするため（`FLAG_UPDATE_CURRENT` だけでは同じ
     * requestCode の PendingIntent が1つに畳まれる）。
     */
    private fun launchIntent(sessionId: Long): PendingIntent? {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(WalkNotifierIntents.EXTRA_REVIEW_SESSION_ID, sessionId)
            }
            ?: return null
        return PendingIntent.getActivity(
            context,
            sessionId.toInt(),
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

/**
 * 通知のタップから振り返りを開くための取り決め（Android専用）。
 *
 * 通知を作るのは shared（[AndroidWalkNotifier]）だが、受けるのは composeApp の
 * `MainActivity` なので、キー名を両方から見える場所に置く。
 */
object WalkNotifierIntents {

    /** 「おかえり」通知が載せる `walk_session.id`（`Long`）。 */
    const val EXTRA_REVIEW_SESSION_ID: String = "com.walkingrpg.extra.REVIEW_SESSION_ID"
}

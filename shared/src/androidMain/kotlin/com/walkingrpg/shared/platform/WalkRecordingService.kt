package com.walkingrpg.shared.platform

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * 記録中の保険となるForeground Service（architecture.md §5）。
 *
 * 記録そのものはアプリ側のコルーチン（`WalkRecorderImpl`）が回している。
 * このサービスはプロセスをフォアグラウンド扱いに保ち、
 * 画面オフ・ロック時にOSから測位を止められる／プロセスを落とされるのを防ぐだけ。
 */
internal class WalkRecordingService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        // 記録の実体はアプリ側のコルーチン。プロセスごと落とされた場合に
        // サービスだけ復活させても記録は再開しないので STICKY にはしない
        // （TODO(#4): 中断セッションの再開はセッション管理の本実装で扱う）
        return START_NOT_STICKY
    }

    private fun startInForeground() {
        ensureChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "散歩の記録",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "散歩中の位置記録を継続するための常駐通知"
                setShowBadge(false)
            },
        )
    }

    private fun buildNotification(): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("散歩を記録しています")
            .setContentText("画面はONのまま持ち歩いてください")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .apply { launchIntent()?.let { setContentIntent(it) } }
            .build()

    private fun launchIntent(): PendingIntent? {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    companion object {
        private const val CHANNEL_ID = "walk_recording"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            context.startForegroundService(Intent(context, WalkRecordingService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WalkRecordingService::class.java))
        }
    }
}

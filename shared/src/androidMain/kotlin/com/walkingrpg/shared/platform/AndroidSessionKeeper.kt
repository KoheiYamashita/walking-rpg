package com.walkingrpg.shared.platform

import android.content.Context
import android.util.Log

/**
 * Foreground Service の起動・停止（architecture.md §5 の「保険」）。
 *
 * 基本運用は画面ON・フォアグラウンド。ここが効くのは誤って画面オフ・ロックした場合だけ。
 * サービスの起動に失敗しても記録自体（フォアグラウンドでの測位）は続くので、
 * ここでは記録を止めずログに残すに留める。
 */
internal class AndroidSessionKeeper(
    private val context: Context,
) : SessionKeeper {

    override fun start() {
        runCatching { WalkRecordingService.start(context) }
            .onFailure { Log.w(TAG, "Foreground Serviceを開始できませんでした", it) }
    }

    override fun stop() {
        runCatching { WalkRecordingService.stop(context) }
            .onFailure { Log.w(TAG, "Foreground Serviceを停止できませんでした", it) }
    }

    private companion object {
        const val TAG = "AndroidSessionKeeper"
    }
}

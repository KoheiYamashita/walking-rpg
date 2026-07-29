package com.walkingrpg.shared.platform

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

/**
 * Androidの振動（design.md §3「振動1回＝『何かが起きた』の印」）。
 *
 * 端末によっては振動できない（モーターが無い・OSが抑制している）が、
 * それで散歩の記録が変わることはないのでログに残すだけにする
 * （[AndroidSessionKeeper] / [AndroidWalkNotifier] と同じ方針）。
 */
internal class AndroidHaptics(
    private val context: Context,
) : Haptics {

    /**
     * API 31 で [Vibrator] の直接取得が非推奨になり、[VibratorManager] 経由になった。
     * minSdk は26なので両方の経路が要る（`AndroidLocationPermissionController` と同じ分岐の作り）。
     */
    private val vibrator: Vibrator? by lazy {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Vibrator::class.java)
            }
        }.getOrNull()
    }

    override fun vibrateOnce() {
        runCatching {
            val vibrator = vibrator ?: return
            if (!vibrator.hasVibrator()) return
            vibrator.vibrate(
                VibrationEffect.createOneShot(
                    DURATION_MS,
                    VibrationEffect.DEFAULT_AMPLITUDE,
                ),
            )
        }.onFailure { Log.w(TAG, "振動できませんでした", it) }
    }

    private companion object {
        const val TAG = "AndroidHaptics"

        /**
         * 40ms：ポケットの中でも気づき、通知の振動（数百ms）ほど主張しない長さ。
         * 長くしても伝わる情報は増えない（印であって内容ではない）。
         */
        const val DURATION_MS = 40L
    }
}

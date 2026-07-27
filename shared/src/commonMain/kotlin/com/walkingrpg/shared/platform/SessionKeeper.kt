package com.walkingrpg.shared.platform

/**
 * 記録中にOSからプロセスを殺されないようにする保険（architecture.md §5）。
 *
 * 基本は「画面ON・アプリフォアグラウンド」で歩く運用だが、
 * 誤って画面オフ・ロックしても記録が途切れないための併用機構：
 * - Android: Foreground Service
 * - iOS: バックグラウンド測位（#3で検討。現状はスタブ）
 */
interface SessionKeeper {
    fun start()
    fun stop()
}

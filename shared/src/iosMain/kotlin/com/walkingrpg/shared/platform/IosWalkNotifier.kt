package com.walkingrpg.shared.platform

/**
 * iOSの通知スタブ。動作検証はAndroid優先方針（issue #2 備考）なので、
 * UNUserNotificationCenter による実装はiOSスパイク（#3）で入れる。
 *
 * 測位スタブ（`IosLocationStubs.kt`）と違って**黙って何もしない**：
 * 通知が出せなくてもセッションは正しく畳まれる（`WalkNotifier` のコメント参照）ので、
 * ここで例外を投げると自動終了そのものを壊してしまう。
 */
internal class IosWalkNotifier : WalkNotifier {
    // TODO(#3): UNUserNotificationCenter でローカル通知を出す
    override fun notifyHomecoming(durationMs: Long) = Unit
}

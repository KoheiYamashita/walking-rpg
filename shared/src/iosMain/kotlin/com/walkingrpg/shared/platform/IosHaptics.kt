package com.walkingrpg.shared.platform

/**
 * iOSの振動スタブ。動作検証はAndroid優先方針（issue #2 備考）なので、
 * Core Haptics（`CHHapticEngine`）による実装はiOSスパイク（#3）で入れる。
 *
 * [IosWalkNotifier] と同じく**黙って何もしない**：振動が出せなくても
 * イベントの記録も散歩の記録も正しく残る（[Haptics] のコメント参照）。
 */
internal class IosHaptics : Haptics {
    // TODO(#3): Core Haptics で短い1回の振動を出す
    override fun vibrateOnce() = Unit
}

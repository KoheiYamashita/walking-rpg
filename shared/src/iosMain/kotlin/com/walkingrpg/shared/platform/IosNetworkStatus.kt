package com.walkingrpg.shared.platform

import com.walkingrpg.shared.domain.llm.NetworkStatus

/**
 * iOSの回線種別判定スタブ。動作検証はAndroid優先方針（issue #2 備考）なので、
 * `NWPathMonitor`（`nw_path_is_expensive` / `nw_path_is_constrained` の否定）による
 * 実装はiOSスパイク（#3）で入れる。
 *
 * それまでは常に `false`＝**従量課金の回線として扱う**。[NetworkStatus.isUnmetered] が
 * 「取れないときは生成しない側に倒す」契約なので、スタブでも例外を投げずにこの値を返す。
 * iOSではLLMの事前バッチが走らないだけで、地点フレーバーは定型文で成立し
 * （design.md §7）、散歩の記録・成長には影響しない。
 *
 * `NWPathMonitor` は更新をコールバックで受ける非同期APIなので、
 * #3 では最新の経路を保持して同期的に読める形にする必要がある（この関数は同期）。
 */
internal class IosNetworkStatus : NetworkStatus {
    // TODO(#3): NWPathMonitor で最新の経路を保持し、従量でなければ true を返す
    override fun isUnmetered(): Boolean = false
}

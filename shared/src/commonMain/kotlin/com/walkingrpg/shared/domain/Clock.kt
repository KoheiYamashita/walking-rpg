package com.walkingrpg.shared.domain

/**
 * 時刻の注入口（architecture.md §2「UseCaseは時刻・乱数を使わず `Clock` 等を注入」）。
 *
 * ドメイン層が現在時刻を直接読まないための境界。テストでは固定値を返す実装を差す。
 */
interface Clock {
    /** epoch millis。 */
    fun nowMillis(): Long
}

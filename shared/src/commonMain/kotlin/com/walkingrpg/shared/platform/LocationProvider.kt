package com.walkingrpg.shared.platform

import com.walkingrpg.shared.domain.walk.LocationFix
import kotlinx.coroutines.flow.Flow

/**
 * 測位のプラットフォーム境界（Android: FusedLocationProvider / iOS: CoreLocation）。
 *
 * 実装はDIで注入する（`platformModule`）。ドメイン層からこの型は見えない。
 */
interface LocationProvider {

    /**
     * 測位を購読する。収集を止めると測位も止まる（cold flow）。
     *
     * 権限がない・測位できないときは [com.walkingrpg.shared.domain.walk.LocationUnavailableException]
     * を投げる。黙って空のFlowにはしない（欠落を検証する用途なので握り潰さない）。
     *
     * @param intervalMs 希望する取得間隔。OSは必ずしも守らない（architecture.md §5 の1〜3秒）
     */
    fun updates(intervalMs: Long = DEFAULT_INTERVAL_MS): Flow<LocationFix>

    companion object {
        /** design.md / architecture.md §5 の「1〜3秒間隔」の下限側を既定にする。 */
        const val DEFAULT_INTERVAL_MS: Long = 1_000L
    }
}

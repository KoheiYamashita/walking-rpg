package com.walkingrpg.shared.data

import com.walkingrpg.shared.domain.Clock
import com.walkingrpg.shared.platform.currentTimeMillis

/** 実時刻を返す [Clock]。テストでは差し替える。 */
internal class SystemClock : Clock {
    override fun nowMillis(): Long = currentTimeMillis()
}

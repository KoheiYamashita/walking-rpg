package com.walkingrpg.shared.platform

/** 現在時刻（epoch millis）。`Clock` の実装がこれを包む。 */
expect fun currentTimeMillis(): Long

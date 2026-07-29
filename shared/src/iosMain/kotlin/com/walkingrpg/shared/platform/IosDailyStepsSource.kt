package com.walkingrpg.shared.platform

import com.walkingrpg.shared.domain.steps.CalendarDay
import com.walkingrpg.shared.domain.steps.DailySteps
import com.walkingrpg.shared.domain.steps.DailyStepsSource

/**
 * iOSの歩数取得スタブ。HealthKit の実装は後続issue。
 *
 * 常に `null`（＝取れなかった）を返す。[DailyStepsSource] は「取れないのが普通」の
 * 契約なので、スタブでも例外を投げない（他のiOSスタブのように実行時エラーにすると、
 * 押し忘れ救済が未実装なだけで起動時の処理が落ちてしまう）。
 * 取り込まれなければ `step_import` に行が増えないだけで、他の機能は影響を受けない。
 */
internal class IosDailyStepsSource : DailyStepsSource {
    override suspend fun dailySteps(day: CalendarDay): DailySteps? = null
}

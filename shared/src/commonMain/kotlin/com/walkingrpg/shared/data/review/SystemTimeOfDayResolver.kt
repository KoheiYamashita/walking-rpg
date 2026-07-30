package com.walkingrpg.shared.data.review

import com.walkingrpg.shared.domain.review.TimeOfDay
import com.walkingrpg.shared.domain.review.TimeOfDayResolver
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * 端末のタイムゾーンで時間帯を出す [TimeOfDayResolver]（`SystemCalendarDays` と同じ流儀）。
 *
 * タイムゾーンは呼ぶたびに読む：旅行先で歩いた散歩は、その土地の夕方として語られる方が
 * 「本物の記録の語り手」（design.md §5）として自然。
 */
internal class SystemTimeOfDayResolver : TimeOfDayResolver {

    override fun timeOfDay(epochMillis: Long): TimeOfDay = TimeOfDay.ofHour(
        Instant.fromEpochMilliseconds(epochMillis)
            .toLocalDateTime(TimeZone.currentSystemDefault())
            .hour,
    )
}

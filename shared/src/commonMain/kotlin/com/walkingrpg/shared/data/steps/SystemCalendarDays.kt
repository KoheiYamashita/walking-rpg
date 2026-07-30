package com.walkingrpg.shared.data.steps

import com.walkingrpg.shared.domain.Clock
import com.walkingrpg.shared.domain.steps.CalendarDay
import com.walkingrpg.shared.domain.steps.CalendarDays
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime

/**
 * 端末のタイムゾーンで暦日を出す [CalendarDays]。テストでは差し替える。
 *
 * 現在時刻は [Clock] から取る（時刻の入口を2つに増やさない）。
 * タイムゾーンは呼ぶたびに読む：旅行や日付変更線をまたいでも、
 * 「その人にとっての今日」に追従するのが歩数計側の集計と合う。
 */
internal class SystemCalendarDays(
    private val clock: Clock,
) : CalendarDays {

    override fun today(): CalendarDay = Instant.fromEpochMilliseconds(clock.nowMillis())
        .toLocalDateTime(TimeZone.currentSystemDefault())
        .date
        .toCalendarDay()

    override fun previousDay(day: CalendarDay): CalendarDay =
        LocalDate.parse(day.iso).minus(1, DateTimeUnit.DAY).toCalendarDay()

    // 時刻→暦日の変換だけで、現在時刻は読まない（同じ入力からは必ず同じ日が出る）。
    override fun day(epochMillis: Long): CalendarDay = Instant.fromEpochMilliseconds(epochMillis)
        .toLocalDateTime(TimeZone.currentSystemDefault())
        .date
        .toCalendarDay()

    override fun daysBetween(from: CalendarDay, until: CalendarDay): Int =
        LocalDate.parse(from.iso).daysUntil(LocalDate.parse(until.iso))
}

/** `LocalDate.toString()` は ISO-8601（'YYYY-MM-DD'）。 */
private fun LocalDate.toCalendarDay(): CalendarDay = CalendarDay(toString())

package com.walkingrpg.shared.data.steps

import com.walkingrpg.shared.domain.Clock
import com.walkingrpg.shared.domain.snapshot.CalendarMonth
import com.walkingrpg.shared.domain.snapshot.MonthRange
import com.walkingrpg.shared.domain.steps.CalendarDay
import com.walkingrpg.shared.domain.steps.CalendarDays
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.daysUntil
import kotlinx.datetime.minus
import kotlinx.datetime.plus
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

    // 'YYYY-MM-DD' の先頭7文字がそのまま 'YYYY-MM'。日付側の形は CalendarDay が保証している。
    override fun monthOf(day: CalendarDay): CalendarMonth = CalendarMonth(day.iso.take(MONTH_LENGTH))

    override fun previousMonth(month: CalendarMonth): CalendarMonth =
        month.firstDay().minus(1, DateTimeUnit.MONTH).toCalendarMonth()

    /**
     * 月初 0:00 〜 翌月初 0:00 を epoch millis で返す。
     *
     * 月初を「その日の 0:00」ではなく [LocalDate.atStartOfDayIn] で解くのは、
     * 夏時間の開始日が月初に当たる地域で 0:00 が存在しないことがあるため
     * （kotlinx-datetime が実在する最初の瞬間へ寄せてくれる）。
     */
    override fun monthRange(month: CalendarMonth): MonthRange {
        val timeZone = TimeZone.currentSystemDefault()
        val firstDay = month.firstDay()
        return MonthRange(
            fromMs = firstDay.atStartOfDayIn(timeZone).toEpochMilliseconds(),
            untilMs = firstDay.plus(1, DateTimeUnit.MONTH)
                .atStartOfDayIn(timeZone)
                .toEpochMilliseconds(),
        )
    }

    private companion object {
        /** 'YYYY-MM' の文字数。 */
        const val MONTH_LENGTH = 7
    }
}

/** `LocalDate.toString()` は ISO-8601（'YYYY-MM-DD'）。 */
private fun LocalDate.toCalendarDay(): CalendarDay = CalendarDay(toString())

/** `LocalDate` を 'YYYY-MM' に落とす（日は捨てる）。 */
private fun LocalDate.toCalendarMonth(): CalendarMonth = CalendarMonth(toString().take(7))

/** その月の1日。月の計算は `LocalDate` に預けるので、いったん日付に戻す。 */
private fun CalendarMonth.firstDay(): LocalDate = LocalDate.parse("$iso-01")

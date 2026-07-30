package com.walkingrpg.shared.data.steps

import com.walkingrpg.shared.domain.Clock
import com.walkingrpg.shared.domain.snapshot.CalendarMonth
import com.walkingrpg.shared.domain.steps.CalendarDay
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import java.util.TimeZone as JavaTimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 暦の計算（[SystemCalendarDays]）を**実際のタイムゾーン**で検証する。
 *
 * ここが androidUnitTest（JVM）なのは、プロセスの既定タイムゾーンを差し替えるAPI
 * （`java.util.TimeZone.setDefault`）がJVM限定だから。ドメインのテスト
 * （`GenerateMonthlySnapshotsUseCaseTest`）は暦を潰したフェイクで足りるので、
 * 本物の暦（月の日数・うるう年・年をまたぐ月・UTCとのずれ）はここだけで見る。
 */
class SystemCalendarDaysTest {

    private var original: JavaTimeZone? = null

    @BeforeTest
    fun setUp() {
        original = JavaTimeZone.getDefault()
        // UTC+9（夏時間なし）。UTCと日付がずれる時間帯があるので、
        // 「端末ローカルの暦」で切れているかが見える
        JavaTimeZone.setDefault(JavaTimeZone.getTimeZone("Asia/Tokyo"))
    }

    @AfterTest
    fun tearDown() {
        original?.let { JavaTimeZone.setDefault(it) }
    }

    private fun calendarDays(nowMs: Long = 0L) = SystemCalendarDays(
        clock = object : Clock {
            override fun nowMillis(): Long = nowMs
        },
    )

    /** その日のローカル 0:00（テストの意図を「日付」で書くための逆引き）。 */
    private fun localStartOfDayMs(iso: String): Long {
        val days = calendarDays()
        // 総当たりはしない：UTC 0:00 から前後1日ぶんを1分刻みで探すより、
        // monthRange の実装（atStartOfDayIn）を1日ぶんの月に閉じ込めて使う方が確実
        val month = CalendarMonth(iso.take(MONTH_LENGTH))
        val dayOfMonth = iso.substring(MONTH_LENGTH + 1).toInt()
        return days.monthRange(month).fromMs + (dayOfMonth - 1) * MILLIS_PER_DAY
    }

    @Test
    fun 日付から月が取れる() {
        val days = calendarDays()

        assertEquals(CalendarMonth("2026-06"), days.monthOf(CalendarDay("2026-06-01")))
        assertEquals(CalendarMonth("2026-06"), days.monthOf(CalendarDay("2026-06-30")))
        assertEquals(CalendarMonth("2026-12"), days.monthOf(CalendarDay("2026-12-31")))
    }

    @Test
    fun 今月は今日から出る() {
        // 2026-07-01 のローカル 0:00 ちょうど
        val days = calendarDays(nowMs = localStartOfDayMs("2026-07-01"))

        assertEquals(CalendarDay("2026-07-01"), days.today())
        assertEquals(CalendarMonth("2026-07"), days.monthOf(days.today()))
    }

    @Test
    fun 前月は年をまたいでも1つ前に進む() {
        val days = calendarDays()

        assertEquals(CalendarMonth("2026-06"), days.previousMonth(CalendarMonth("2026-07")))
        assertEquals(CalendarMonth("2025-12"), days.previousMonth(CalendarMonth("2026-01")))
        assertEquals(CalendarMonth("2026-02"), days.previousMonth(CalendarMonth("2026-03")))
    }

    @Test
    fun 月の範囲は月初から翌月初まで() {
        val days = calendarDays()
        val june = days.monthRange(CalendarMonth("2026-06"))

        // 30日ぶん（Asia/Tokyo は夏時間なしなので、ちょうど 30 × 24h）
        assertEquals(30L * MILLIS_PER_DAY, june.untilMs - june.fromMs)
        assertEquals(CalendarDay("2026-06-01"), days.day(june.fromMs))
        assertEquals(CalendarDay("2026-06-30"), days.day(june.untilMs - 1))
        assertEquals(CalendarDay("2026-07-01"), days.day(june.untilMs), "上端は翌月の1日目")
    }

    @Test
    fun 月の長さは月ごとに違う() {
        val days = calendarDays()

        assertEquals(31L * MILLIS_PER_DAY, days.monthRange(CalendarMonth("2026-07")).let { it.untilMs - it.fromMs })
        assertEquals(28L * MILLIS_PER_DAY, days.monthRange(CalendarMonth("2026-02")).let { it.untilMs - it.fromMs })
        // うるう年
        assertEquals(29L * MILLIS_PER_DAY, days.monthRange(CalendarMonth("2028-02")).let { it.untilMs - it.fromMs })
    }

    @Test
    fun 月の範囲は隣の月と隙間なく繋がる() {
        val days = calendarDays()

        assertEquals(
            days.monthRange(CalendarMonth("2026-06")).untilMs,
            days.monthRange(CalendarMonth("2026-07")).fromMs,
            "先月の上端と当月の下端が同じ＝どの時刻もちょうど1つの月に入る",
        )
        assertEquals(
            days.monthRange(CalendarMonth("2025-12")).untilMs,
            days.monthRange(CalendarMonth("2026-01")).fromMs,
            "年をまたいでも繋がる",
        )
    }

    @Test
    fun 月の境界は端末ローカルでUTCではない() {
        val days = calendarDays()
        val june = days.monthRange(CalendarMonth("2026-06"))

        // Asia/Tokyo の 6/1 0:00 は UTC では 5/31 15:00
        val utc = Instant.fromEpochMilliseconds(june.fromMs).toLocalDateTime(TimeZone.UTC)
        assertEquals(5, utc.monthNumber)
        assertEquals(31, utc.dayOfMonth)
        assertEquals(15, utc.hour)
    }

    @Test
    fun 夏時間のある地域でも月初が解ける() {
        // 2026-03-01 0:00 が存在しない、という事故（夏時間の開始が月初）を踏まないこと。
        // Asia/Tehran のような月初に切り替わる地域が代表例だが、
        // ここでは「切り替えのある地域で例外にならず、月が隙間なく繋がる」ことを見る
        JavaTimeZone.setDefault(JavaTimeZone.getTimeZone("America/Santiago"))
        val days = calendarDays()

        CalendarMonth("2026-01").let { first ->
            var month = first
            repeat(MONTHS_TO_WALK) {
                val range = days.monthRange(month)
                assertTrue(range.fromMs < range.untilMs, "$month の範囲が壊れている")
                val next = days.monthRange(days.previousMonth(month))
                assertEquals(range.fromMs, next.untilMs, "$month と前月が繋がっていない")
                month = days.previousMonth(month)
            }
        }
    }

    private companion object {
        const val MILLIS_PER_DAY = 24L * 60L * 60L * 1_000L
        const val MONTH_LENGTH = 7
        const val MONTHS_TO_WALK = 24
    }
}

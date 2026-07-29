package com.walkingrpg.shared.domain.steps

/**
 * 暦日の注入口（architecture.md §2「UseCaseは時刻・乱数を使わず `Clock` 等を注入」）。
 *
 * `Clock` が epoch millis の境界なのに対し、こちらは「端末ローカルの暦日」の境界。
 * 現在時刻から今日を出すにも前日を出すにもタイムゾーンと暦の知識が要るので、
 * 純Kotlinのドメイン層には置かず、データ層の実装（`SystemCalendarDays`）に預ける。
 */
interface CalendarDays {

    /** 端末ローカルの今日。 */
    fun today(): CalendarDay

    /** [day] の前日。 */
    fun previousDay(day: CalendarDay): CalendarDay
}

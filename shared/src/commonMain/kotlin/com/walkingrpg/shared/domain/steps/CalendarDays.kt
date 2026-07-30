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

    /**
     * [epochMillis] が端末ローカルのどの暦日か。
     *
     * 現在時刻とは無関係な変換なので、**同じ入力からは必ず同じ日が出る**
     * （タイムゾーン設定を変えない限り）。パートナーの一言（issue #16）が
     * 「そのセッションの時刻から見た過去」だけを材料にできるのはこの性質のおかげで、
     * [today] を使うと時間が経つだけでプロンプトが変わってしまう
     * （＝毎回定型文＋再生成の課金ループ。`GetWalkRemarkContextUseCase` のKDoc）。
     */
    fun day(epochMillis: Long): CalendarDay

    /**
     * [from] から [until] までの日数（暦日の差）。[until] が後なら正、前なら負。
     *
     * epoch millis の差を86400000で割る形にしないのは、夏時間・うるう秒で
     * 「26時間前なのに1日前ではない」がありうるため。暦の計算は
     * ドメインではやらず実装（`SystemCalendarDays`）に預ける。
     */
    fun daysBetween(from: CalendarDay, until: CalendarDay): Int
}

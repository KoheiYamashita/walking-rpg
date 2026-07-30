package com.walkingrpg.shared.domain.steps

import com.walkingrpg.shared.domain.snapshot.CalendarMonth
import com.walkingrpg.shared.domain.snapshot.MonthRange

/**
 * 暦日の注入口（architecture.md §2「UseCaseは時刻・乱数を使わず `Clock` 等を注入」）。
 *
 * `Clock` が epoch millis の境界なのに対し、こちらは「端末ローカルの暦日」の境界。
 * 現在時刻から今日を出すにも前日を出すにもタイムゾーンと暦の知識が要るので、
 * 純Kotlinのドメイン層には置かず、データ層の実装（`SystemCalendarDays`）に預ける。
 *
 * 月の計算（issue #17）も同じ理由でここに集める。型（[CalendarMonth] / [MonthRange]）は
 * 今のところ月次スナップショットのための語彙なので `domain/snapshot` に置いてあり、
 * この境界が持つのは**暦の知識だけ**（うるう年・夏時間・タイムゾーンをまたぐ月初）。
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

    /**
     * [day] が属する月。
     *
     * 「今月」は `monthOf(today())`。`today()` と別に「今月」の口を作らないのは、
     * 時刻の入口を増やさない（`SystemCalendarDays` のKDoc）方針と同じ理由。
     */
    fun monthOf(day: CalendarDay): CalendarMonth

    /**
     * [month] の前月。年をまたぐ（'2026-01' → '2025-12'）。
     *
     * 生成対象の月を数える唯一の手段なので、**必ず厳密に1つ前へ進む**
     * （`GenerateMonthlySnapshotsUseCase` はこれを使って先月から遡り、
     * 停止条件をいちばん古い月との比較で書いている）。
     */
    fun previousMonth(month: CalendarMonth): CalendarMonth

    /**
     * [month] の epoch millis 範囲（月初 0:00 〜 翌月初 0:00、端末ローカル）。
     *
     * 月の日数（28〜31）も夏時間による月初のずれもタイムゾーンの知識なので、
     * `86400000 × 30` のような計算をドメインに書かせない。
     */
    fun monthRange(month: CalendarMonth): MonthRange
}

package com.walkingrpg.shared.domain.review

/**
 * 散歩の時間帯（design.md §10「分岐 × 季節 × 時間帯 × 天候」の時間帯の軸）。
 *
 * パートナーの一言（`WalkReviewRemarkPromptBuilder`）に渡す事実のひとつ。
 * 同じ距離・同じ道でも、朝と夕方で書ける情景が変わる＝単調化対策の安い一手。
 *
 * 段の切り方は生活時間の粗い区切りで足りる（天候と違って外部APIが決める値ではない）。
 */
enum class TimeOfDay {
    /** 明け方〜午前。 */
    MORNING,

    /** 日中。 */
    DAYTIME,

    /** 夕方。design.md §3 のウォークスルー（16:40出発・17:08帰宅）はここ。 */
    EVENING,

    /** 夜。 */
    NIGHT,
    ;

    companion object {
        /**
         * ローカルの時（0〜23）から時間帯を引く（純関数）。
         *
         * 範囲外の値は日中に寄せる（時刻の取得側が壊れても文章が出ないより出る方がよい）。
         */
        fun ofHour(hourOfDay: Int): TimeOfDay = when (hourOfDay) {
            in 5..10 -> MORNING
            in 11..15 -> DAYTIME
            in 16..19 -> EVENING
            in 20..23, in 0..4 -> NIGHT
            else -> DAYTIME
        }
    }
}

/**
 * 「その時刻は端末のローカルで何時か」の注入口
 * （architecture.md §2「UseCaseは時刻・乱数を使わず `Clock` 等を注入」）。
 *
 * `Clock` が epoch millis の境界、`CalendarDays` が暦日の境界なのに対し、
 * こちらは**時間帯**の境界。どれもタイムゾーンの知識が要るので、純Kotlinのドメイン層には
 * 置かず、データ層の実装（`SystemTimeOfDayResolver`）に預ける。
 */
interface TimeOfDayResolver {

    /** [epochMillis] の時点が端末ローカルでどの時間帯か。 */
    fun timeOfDay(epochMillis: Long): TimeOfDay
}

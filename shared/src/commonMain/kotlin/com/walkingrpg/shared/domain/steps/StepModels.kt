package com.walkingrpg.shared.domain.steps

/**
 * 端末ローカルの暦日（'YYYY-MM-DD'）。
 *
 * 歩数計が返すのは「その人にとっての1日」の集計なので、時刻（epoch millis）ではなく
 * タイムゾーンを剥がした暦日で扱う。暦の計算（今日・前日）はドメインではやらず
 * [CalendarDays] に預ける（ドメイン層は外部依存なしの純Kotlin。architecture.md §3）。
 */
data class CalendarDay(val iso: String) {
    init {
        require(ISO_PATTERN.matches(iso)) { "日付は 'YYYY-MM-DD' 形式で指定する: $iso" }
    }

    override fun toString(): String = iso

    companion object {
        private val ISO_PATTERN = Regex("""\d{4}-\d{2}-\d{2}""")
    }
}

/**
 * 歩数計から取れた1日ぶんの値（[DailyStepsSource] の戻り値）。
 *
 * 「取れなかった」は `null`（ソース側が返さない）で表し、この型では表さない。
 * 0歩の日と取得できなかった日を同じ形にすると、権限が切れているのか
 * 本当に歩いていないのかが後段で区別できなくなる。
 */
data class DailySteps(
    val day: CalendarDay,
    /** その日の歩数。 */
    val steps: Int,
    /** 歩数計が返した距離（メートル）。距離を出せないソース・権限が無い場合は `null`。 */
    val distanceEstimateMeters: Double?,
)

/**
 * 押し忘れ救済として保存した1日ぶんの記録（`step_import`）。
 *
 * 位置情報が無いので街は育たない（`passage` は作れない）が、
 * 「歩いたのに無かったことになる」も避ける（design.md §3）。
 */
data class StepImport(
    val day: CalendarDay,
    val steps: Int,
    val distanceEstimateMeters: Double?,
)

package com.walkingrpg.shared.domain.review

/**
 * 振り返りのテスト用差し替え。
 *
 * 時間帯はタイムゾーン依存（`SystemTimeOfDayResolver`）なので、テストでは固定する
 * ＝端末のタイムゾーンでプロンプトの中身が変わらない。
 */
internal class FakeTimeOfDayResolver(
    private val timeOfDay: TimeOfDay = TimeOfDay.EVENING,
) : TimeOfDayResolver {

    override fun timeOfDay(epochMillis: Long): TimeOfDay = timeOfDay
}

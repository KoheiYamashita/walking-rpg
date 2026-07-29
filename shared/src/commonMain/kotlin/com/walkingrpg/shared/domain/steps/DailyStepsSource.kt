package com.walkingrpg.shared.domain.steps

/**
 * 歩数計の境界（Android: Health Connect / iOS: HealthKit）。
 *
 * ドメイン層はこのインターフェースだけを知り、実装はDIで注入する
 * （platform層がドメインの定義を実装する。CONTRIBUTING「依存の向き」）。
 */
interface DailyStepsSource {

    /**
     * 指定日（端末ローカルの暦日）の歩数・距離を返す。
     *
     * **取れなかったら `null`**（例外は投げない）。歩数計は
     * 「アプリが入っていない・権限が無い・その日のデータが無い」が普通に起きる補助的な情報源で、
     * 取れないこと自体は異常ではない。ここで例外を投げると、押し忘れ救済という
     * 「おまけ」の失敗が呼び出し側の処理を巻き添えにしてしまう。
     */
    suspend fun dailySteps(day: CalendarDay): DailySteps?
}

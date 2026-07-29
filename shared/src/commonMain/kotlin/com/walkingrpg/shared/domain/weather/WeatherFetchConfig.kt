package com.walkingrpg.shared.domain.weather

/**
 * 天候の後付け取得の調整値。**触る数字は全部ここに集める**（issue #11）。
 *
 * @param giveUpAfterMs セッション終了からこの時間が過ぎても取れないセッションは、
 *  [WeatherCondition.UNKNOWN] で確定させてリトライを打ち切る（下記「諦める理由」）。
 * @param maxFetchesPerRun 1回の実行で外部APIに投げる上限。
 */
data class WeatherFetchConfig(
    val giveUpAfterMs: Long = DEFAULT_GIVE_UP_AFTER_MS,
    val maxFetchesPerRun: Int = DEFAULT_MAX_FETCHES_PER_RUN,
) {
    init {
        require(giveUpAfterMs > 0) { "giveUpAfterMs は正の値" }
        require(maxFetchesPerRun > 0) { "maxFetchesPerRun は正の値" }
    }

    companion object {
        /**
         * 諦めるまでの猶予＝14日。
         *
         * ## 諦める理由
         * 過去日の天候には**プロバイダ側の取得可能期間**がある。無料枠だと
         * Open-Meteo の予報エンドポイントが数十日ぶんの過去を返す一方、
         * OpenWeatherMap の履歴（One Call timemachine）や Visual Crossing は
         * 遡れる範囲・呼べる回数が契約次第で、いずれも「無限に遡れる」ことはない。
         * 取れない問い合わせを起動のたびに投げ続けても、通信と時間を捨てるだけになる。
         *
         * ## なぜ14日か
         * 上限が最も短いプロバイダに合わせて短く切ると、少し旅行に出ただけで
         * 直前の散歩の天候が捨てられる。逆に長くすると、取れないと分かっている問い合わせを
         * 何ヶ月も続ける。3プロバイダのどれでも遡れる見込みがある範囲として2週間を取り、
         * 「2週間アプリを開かなかった散歩は天候不明で確定」という分かりやすい線にした。
         *
         * 天候が付かなくても散歩の記録（真実の源）は無傷で、欠けるのは変奏だけ
         * （architecture.md §8「天候APIの欠測」）。ここは思い切って切ってよい場所。
         */
        const val DEFAULT_GIVE_UP_AFTER_MS: Long = 14L * 24 * 60 * 60 * 1000

        /**
         * 1回の実行で投げる上限＝20件。
         *
         * 長く開かなかった端末を起動した瞬間に、溜まったセッションぶんの
         * リクエストを一斉に投げないための蓋。古い順に処理するので、
         * 打ち切りの期限（[DEFAULT_GIVE_UP_AFTER_MS]）が近いものから埋まる。
         * 残りは次の起動・次の散歩終了時に続きから埋まる（この取り込みは冪等）。
         */
        const val DEFAULT_MAX_FETCHES_PER_RUN: Int = 20

        /** 既定値。差し替えはDI（`sharedModule`）で行う。UIからは触らせない。 */
        val DEFAULT: WeatherFetchConfig = WeatherFetchConfig()
    }
}

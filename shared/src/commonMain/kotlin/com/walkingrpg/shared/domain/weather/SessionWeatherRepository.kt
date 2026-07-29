package com.walkingrpg.shared.domain.weather

/**
 * `session_weather` の永続化境界（architecture.md §2「Repository」）。
 *
 * ドメイン層はこのインターフェースだけを知る。SQLDelightのスキーマ変換は
 * データ層（`SessionWeatherRepositoryImpl`）に閉じる。
 */
interface SessionWeatherRepository {

    /**
     * 1セッションぶんを保存する。同じセッションの行があれば置き換える（1セッション1行）。
     *
     * 置き換えにするのは、取り込みを何度流しても同じ状態に収束させるため
     * （`FetchMissingSessionWeatherUseCase` の冪等性はここに乗っている）。
     */
    suspend fun save(weather: SessionWeather)

    /** 指定セッションの天候。**まだ取得していなければ `null`**（[SessionWeather] のKDoc）。 */
    suspend fun weather(sessionId: Long): SessionWeather?

    /**
     * 終了済みで、まだ `session_weather` の行が無いセッションのID（古い順）。
     *
     * これが「次回起動時リトライ」の対象そのもの。記録中（`ended_at IS NULL`）の
     * セッションは含まない：まだ位置も時刻も確定していないので訊く先が決まらない。
     */
    suspend fun sessionIdsWithoutWeather(): List<Long>
}

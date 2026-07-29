package com.walkingrpg.shared.domain.weather

import com.walkingrpg.shared.domain.setup.WeatherProviderChoice

/**
 * 天候APIの境界（architecture.md §1「`WeatherProvider`抽象＋3実装」）。
 *
 * ドメイン層はこのインターフェースしか知らない。プロバイダ固有のURL・パラメータ・
 * レスポンス形式と、そのコード体系から [WeatherCondition] への正規化は
 * データ層（`data/weather` の各プロバイダ実装）に閉じる。
 *
 * 実装は3つ：Open-Meteo（キー不要・既定）／OpenWeatherMap／Visual Crossing。
 * どれを使うかはユーザーの設定（`WeatherSettings`）で決まり、その振り分けは
 * [WeatherProviderSelector] が行う。
 */
interface WeatherProvider {

    /** この実装が受け持つ選択肢。[WeatherProviderSelector] の対応付けに使う。 */
    val choice: WeatherProviderChoice

    /**
     * [query] の時刻・地点の天候を1件取る。
     *
     * @param apiKey キーが要らないプロバイダ（Open-Meteo）では空文字。
     *  秘密なので、実装は**例外メッセージ・ログにこの値を出さない**
     *  （キーはURLのクエリに乗るので、URLごと出さないこと）。
     * @throws WeatherUnavailableException 通信・応答の不備で取れなかったとき。
     *  呼び出し側はこれを「未取得」として扱い、次回起動時にリトライする。
     */
    suspend fun observe(query: WeatherQuery, apiKey: String): WeatherObservation
}

/**
 * 選択されたプロバイダを解決する（design.md §9「プロバイダは差し替え可能」）。
 *
 * ドメイン層が3実装の存在を知らずに「設定で選ばれた実装」へ辿り着くための1枚。
 * 実装（`HttpWeatherProviderSelector`）は enum に対する `when` なので、
 * プロバイダを増やしたらコンパイルエラーで気付ける。
 */
interface WeatherProviderSelector {
    fun provider(choice: WeatherProviderChoice): WeatherProvider
}

/**
 * 天候が取れなかったとき（通信失敗・エラー応答・応答に値が無い）。
 *
 * **原因の詳細（URL・応答本文）は載せない**：URLにはAPIキーと座標が乗るので、
 * 例外メッセージに混ぜるとログ・エラー表示の経路でそのまま漏れる。
 * 分かるのは「どのプロバイダで」「どの段階で」失敗したかまでにしておく。
 */
class WeatherUnavailableException(message: String) : Exception(message)

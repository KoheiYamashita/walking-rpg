package com.walkingrpg.shared.domain.weather

import com.walkingrpg.shared.domain.walk.LocationSample
import kotlin.math.pow
import kotlin.math.round

/**
 * 天候の後付け取得（issue #11・design.md §9「天候は後付けで確定する」）のドメインモデル。
 *
 * 純Kotlin。Ktorもプロバイダ固有のコードもここには現れない
 * （通信とレスポンスの正規化はデータ層の仕事）。
 */

/**
 * プロバイダ横断の共通天候（architecture.md §1「プロバイダは差し替え可能」）。
 *
 * 3プロバイダのコード体系（WMO weather code / OpenWeatherMap condition id /
 * Visual Crossing icon）はそれぞれ粒度が違うので、**3機能（分岐成長・条件到達・図鑑変奏）が
 * 実際に区別したい粒度**まで落として揃える。design.md §4.4 の「天候3」が
 * 求めているのは晴れ・曇り・雨の3分類なので、それを満たしたうえで
 * 「雪」「霧」「雷」だけを別枠に残した（どれも滅多に来ないぶん、
 * 来たときの変奏の価値が高い）。気温は別（[WeatherObservation.temperatureCelsius]）。
 *
 * 各プロバイダの対応表は実装側（`data/weather` の各プロバイダ実装）に持たせる。
 * ここに集約しないのは、対応表がプロバイダのAPI仕様そのもの＝
 * 相手が変えたら追随するもので、ドメインの語彙ではないから。
 */
enum class WeatherCondition {
    /** 快晴〜晴れ（雲がほぼ無い〜少ない）。 */
    CLEAR,

    /** 曇り（部分的な曇り〜本曇り）。 */
    CLOUDY,

    /** 雨（霧雨・にわか雨・凍雨を含む）。 */
    RAIN,

    /** 雪（にわか雪・霧雪を含む）。 */
    SNOW,

    /** 霧・靄など視程が落ちる天候。 */
    FOG,

    /** 雷雨。 */
    THUNDER,

    /**
     * 天候不明。**変奏・条件判定からは除外する**（architecture.md §8「天候APIの欠測」）。
     *
     * 「まだ取れていない」とは違うことに注意：未取得は `session_weather` に
     * 行が無い状態で表し、この値は「もう取れないと諦めて確定させた」か
     * 「プロバイダが当アプリの知らないコードを返した」を意味する。
     * 区別の設計は [SessionWeather] のKDoc。
     */
    UNKNOWN,
}

/**
 * プロバイダから受け取った観測値（1地点・1時刻ぶん）。
 *
 * @param temperatureCelsius 気温（℃）。プロバイダが返さなかった場合は `null`
 *  （0℃と「取れなかった」を同じ形にしない）。
 */
data class WeatherObservation(
    val condition: WeatherCondition,
    val temperatureCelsius: Double? = null,
)

/**
 * 天候APIに投げる問い合わせ（「いつ・どこ」）。
 *
 * **座標はここに入る時点で既に丸められている**（[WeatherQueryPlanner]）。
 * 外部APIに送る値がこの型しか無いようにして、生の測位サンプルが
 * そのまま通信経路に乗る道を塞いでいる（CONTRIBUTING.md「位置情報の扱い」）。
 */
data class WeatherQuery(
    /** 問い合わせる時刻（epoch millis）。 */
    val timestampMs: Long,
    val latitude: Double,
    val longitude: Double,
)

/**
 * 1セッションぶんの確定した天候（`session_weather`）。
 *
 * ## 「未取得」と「天候不明で確定」を分ける
 *
 * - **行が無い** ＝ まだ取れていない。次回起動時のリトライ対象
 *   （design.md §9「失敗時は次回起動時リトライ」）
 * - **行があって [condition] が [WeatherCondition.UNKNOWN]** ＝ 諦めて確定させた。
 *   もうリトライしない。変奏・条件判定からは除外される
 *
 * 同じ「天候が分からない」でも、前者は時間が解決するが後者はしない。
 * 1つの状態にまとめると、永遠に埋まらないセッションを毎回の起動で
 * 問い合わせ続けることになる（過去日の天候APIには取得可能期間の限界があるので、
 * 古いセッションは待っても取れない）。
 *
 * 諦める条件は [WeatherFetchConfig] とその利用側 `FetchMissingSessionWeatherUseCase` に書いてある。
 *
 * @param fetchedAtMs この行を書いた時刻（取得時刻。セッションの時刻ではない）。
 */
data class SessionWeather(
    val sessionId: Long,
    val condition: WeatherCondition,
    val temperatureCelsius: Double?,
    val fetchedAtMs: Long,
) {
    /** 変奏・条件判定に使ってよいか（[WeatherCondition.UNKNOWN] は除外する）。 */
    val isKnown: Boolean get() = condition != WeatherCondition.UNKNOWN
}

/**
 * セッションの測位サンプルから「どの地点・どの時刻の天候を訊くか」を決める（純関数）。
 *
 * ## 自宅座標を外部APIに送らないための丸め
 *
 * 天候APIに渡すのはユーザーの実在の位置なので、送る値は最小限に落とす：
 *
 * 1. **代表点はセッションの中央のサンプル**を使う。最初・最後のサンプルは
 *    自宅の玄関前になりがちで（散歩は自宅発着。design.md §3）、それを送ると
 *    プロバイダのアクセスログに自宅座標が並ぶ。中央なら散歩の折り返し付近＝
 *    自宅から最も離れた辺りになりやすい。自宅そのものを避けるための選択なので、
 *    「平均」ではなく「中央のサンプル」でよい（平均は自宅発着の往復で自宅寄りに戻る）
 * 2. **緯度経度を小数第[COORDINATE_DECIMALS]位に丸める**（≒1km格子）。天候は
 *    数kmスケールの量なので、この丸めで観測値は実質変わらない。逆に丸めない生の座標は
 *    「どの家か」まで分かる粒度で、送る必要がまったく無い
 *
 * 自宅登録のぼかし半径（`HomeAnchor.blurRadiusMeters`。100〜300m）を使って
 * 自宅周辺のサンプルを落とす案も採れるが、そのためには自宅座標をここまで
 * 引き回すことになる。「自宅座標は端末内でしか読まない」（SetupRepositoryImpl のKDoc）を
 * 守るため、自宅を知らなくても成立する上の2段で足りるようにした。
 */
object WeatherQueryPlanner {

    /**
     * 外部に送る緯度経度の小数桁。2桁 ≒ 緯度で約1.1km。
     * 天候の空間解像度（プロバイダ側も数km格子）を下回らない範囲で最も粗い桁。
     */
    const val COORDINATE_DECIMALS: Int = 2

    /**
     * @param samples 1セッションぶんの測位サンプル（時刻順であることを前提にする。
     *  `WalkSessionRepository.samples` はその順で返す）。
     * @return 問い合わせ内容。サンプルが1件も無ければ `null`
     *  （＝位置が分からないので天候は永遠に訊けない。呼び出し側はそれを
     *  「天候不明で確定」として扱う）。
     */
    fun plan(samples: List<LocationSample>): WeatherQuery? {
        if (samples.isEmpty()) return null
        val representative = samples[samples.size / 2]
        return WeatherQuery(
            timestampMs = representative.timestampMs,
            latitude = representative.latitude.roundToDecimals(COORDINATE_DECIMALS),
            longitude = representative.longitude.roundToDecimals(COORDINATE_DECIMALS),
        )
    }
}

private fun Double.roundToDecimals(decimals: Int): Double {
    val factor = 10.0.pow(decimals)
    return round(this * factor) / factor
}

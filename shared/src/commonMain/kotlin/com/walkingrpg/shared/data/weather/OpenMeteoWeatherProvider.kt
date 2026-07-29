package com.walkingrpg.shared.data.weather

import com.walkingrpg.shared.domain.setup.WeatherProviderChoice
import com.walkingrpg.shared.domain.weather.WeatherCondition
import com.walkingrpg.shared.domain.weather.WeatherObservation
import com.walkingrpg.shared.domain.weather.WeatherProvider
import com.walkingrpg.shared.domain.weather.WeatherQuery
import io.ktor.client.HttpClient
import io.ktor.client.request.parameter
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Open-Meteo（https://open-meteo.com/）の [WeatherProvider] 実装。**既定のプロバイダ**。
 *
 * APIキーが要らない（architecture.md §1）ので、セットアップで何も入力しなくても
 * 天候が付く。3プロバイダのうちこれだけが「設定しなくても動く」＝
 * 天候依存の3機能（分岐成長・条件到達・図鑑変奏）の既定の土台になる。
 *
 * 予報エンドポイント（`/v1/forecast`）に時刻範囲（`start_hour` / `end_hour`）を
 * 指定して1時間ぶんだけ取る。過去の時刻も同じエンドポイントで返ってくるが、
 * 遡れる範囲には限りがある（数十日程度）。範囲外は値の入っていない応答になり、
 * 例外＝未取得として次回に持ち越される（最終的には
 * `WeatherFetchConfig.giveUpAfterMs` で「天候不明」に確定する）。
 *
 * 時刻は `timezone=UTC` を明示してUTCで送る。端末のタイムゾーンを送ると
 * 生活圏の手掛かりが1つ増えるうえ、旅行先で取り直したときに解釈がぶれる。
 */
internal class OpenMeteoWeatherProvider(
    private val httpClient: HttpClient,
) : WeatherProvider {

    override val choice: WeatherProviderChoice = WeatherProviderChoice.OPEN_METEO

    /** キー不要なので [apiKey] は使わない（空文字が渡ってくる）。 */
    override suspend fun observe(query: WeatherQuery, apiKey: String): WeatherObservation {
        val hour = query.timestampMs.toUtcHourText()
        val body = httpClient.getWeatherJson(FORECAST_URL, PROVIDER_NAME) {
            parameter("latitude", query.latitude)
            parameter("longitude", query.longitude)
            parameter("hourly", "weather_code,temperature_2m")
            parameter("start_hour", hour)
            parameter("end_hour", hour)
            parameter("timezone", "UTC")
        }

        val hourly = weatherJson.decodeFromString<OpenMeteoResponse>(body).hourly
            ?: missingValue(PROVIDER_NAME)
        val code = hourly.weatherCode.firstOrNull() ?: missingValue(PROVIDER_NAME)
        return WeatherObservation(
            condition = weatherConditionFromWmoCode(code),
            temperatureCelsius = hourly.temperature.firstOrNull(),
        )
    }

    private companion object {
        const val PROVIDER_NAME = "Open-Meteo"
        const val FORECAST_URL = "https://api.open-meteo.com/v1/forecast"
    }
}

/** Open-Meteo が受け取る時刻の書式（`YYYY-MM-DDTHH:MM`）。分は常に00でよい（時間値なので）。 */
private fun Long.toUtcHourText(): String {
    val dateTime = Instant.fromEpochMilliseconds(this).toLocalDateTime(TimeZone.UTC)
    val hour = dateTime.hour.toString().padStart(2, '0')
    return "${dateTime.date}T$hour:00"
}

/**
 * WMO weather code → 共通の [WeatherCondition]。
 *
 * 出典：WMO Code table 4677 を Open-Meteo が簡約したもの
 * （https://open-meteo.com/en/docs の "Weather variable documentation" > WMO Weather interpretation codes）。
 *
 * | コード | 意味 | 対応 |
 * |---|---|---|
 * | 0, 1 | 快晴 / おおむね晴れ | [WeatherCondition.CLEAR] |
 * | 2, 3 | 部分的に曇り / 本曇り | [WeatherCondition.CLOUDY] |
 * | 45, 48 | 霧 / 霧氷の霧 | [WeatherCondition.FOG] |
 * | 51–57 | 霧雨（着氷性を含む） | [WeatherCondition.RAIN] |
 * | 61–67 | 雨（着氷性を含む） | [WeatherCondition.RAIN] |
 * | 80–82 | にわか雨 | [WeatherCondition.RAIN] |
 * | 71–77 | 雪・霧雪 | [WeatherCondition.SNOW] |
 * | 85, 86 | にわか雪 | [WeatherCondition.SNOW] |
 * | 95–99 | 雷雨（雹を伴うものを含む） | [WeatherCondition.THUNDER] |
 * | その他 | 表に無いコード | [WeatherCondition.UNKNOWN] |
 *
 * 1（おおむね晴れ）を晴れ側に入れたのは、他社（OpenWeatherMap の 801「few clouds」、
 * Visual Crossing の `clear-*`）と体感の境目を揃えるため。プロバイダを切り替えたときに
 * 同じ空が違う分類になると、変奏の出方が設定次第で変わってしまう。
 */
internal fun weatherConditionFromWmoCode(code: Int): WeatherCondition = when (code) {
    0, 1 -> WeatherCondition.CLEAR
    2, 3 -> WeatherCondition.CLOUDY
    45, 48 -> WeatherCondition.FOG
    51, 53, 55, 56, 57 -> WeatherCondition.RAIN
    61, 63, 65, 66, 67 -> WeatherCondition.RAIN
    80, 81, 82 -> WeatherCondition.RAIN
    71, 73, 75, 77 -> WeatherCondition.SNOW
    85, 86 -> WeatherCondition.SNOW
    95, 96, 99 -> WeatherCondition.THUNDER
    else -> WeatherCondition.UNKNOWN
}

/**
 * `/v1/forecast` の応答（必要な部分だけ）。
 *
 * `hourly` は「変数名 → 時刻順の配列」で、時刻範囲を1時間に絞ってあるので要素は1つ。
 * 範囲外を指定すると配列が空で返るため、要素の有無で「値があるか」を判定できる。
 */
@Serializable
private data class OpenMeteoResponse(
    val hourly: OpenMeteoHourly? = null,
)

@Serializable
private data class OpenMeteoHourly(
    @SerialName("weather_code") val weatherCode: List<Int?> = emptyList(),
    @SerialName("temperature_2m") val temperature: List<Double?> = emptyList(),
)

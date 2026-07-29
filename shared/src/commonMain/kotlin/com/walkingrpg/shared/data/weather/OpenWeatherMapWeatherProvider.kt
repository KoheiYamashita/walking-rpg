package com.walkingrpg.shared.data.weather

import com.walkingrpg.shared.domain.setup.WeatherProviderChoice
import com.walkingrpg.shared.domain.weather.WeatherCondition
import com.walkingrpg.shared.domain.weather.WeatherObservation
import com.walkingrpg.shared.domain.weather.WeatherProvider
import com.walkingrpg.shared.domain.weather.WeatherQuery
import io.ktor.client.HttpClient
import io.ktor.client.request.parameter
import kotlinx.serialization.Serializable

/**
 * OpenWeatherMap（https://openweathermap.org/）の [WeatherProvider] 実装。**APIキーが要る**。
 *
 * 使うのは One Call API 3.0 の `timemachine`（過去の指定時刻の実況）。
 * 現在天候のエンドポイント（`/data/2.5/weather`）は「いま」しか返さないので、
 * 帰宅後に散歩の時刻を訊くこの用途では使えない
 * （散歩中に雨、帰宅後に晴れ、なら記録すべきは雨）。
 *
 * `dt` は epoch 秒。時刻をそのまま送れるので、Open-Meteo のような
 * タイムゾーンの解釈が要らない。
 *
 * キーはURLのクエリ（`appid`）に乗る。例外にURLを出さない理由は
 * [getWeatherJson] のKDoc。
 */
internal class OpenWeatherMapWeatherProvider(
    private val httpClient: HttpClient,
) : WeatherProvider {

    override val choice: WeatherProviderChoice = WeatherProviderChoice.OPEN_WEATHER_MAP

    override suspend fun observe(query: WeatherQuery, apiKey: String): WeatherObservation {
        if (apiKey.isBlank()) missingApiKey(PROVIDER_NAME)

        val body = httpClient.getWeatherJson(TIMEMACHINE_URL, PROVIDER_NAME) {
            parameter("lat", query.latitude)
            parameter("lon", query.longitude)
            parameter("dt", query.timestampMs / 1000)
            // 気温を℃で受ける（既定はケルビン）。共通モデルは℃で持つ
            parameter("units", "metric")
            parameter("appid", apiKey)
        }

        val entry = weatherJson.decodeFromString<OpenWeatherMapResponse>(body).data.firstOrNull()
            ?: missingValue(PROVIDER_NAME)
        val conditionId = entry.weather.firstOrNull()?.id ?: missingValue(PROVIDER_NAME)
        return WeatherObservation(
            condition = weatherConditionFromOwmConditionId(conditionId),
            temperatureCelsius = entry.temp,
        )
    }

    private companion object {
        const val PROVIDER_NAME = "OpenWeatherMap"
        const val TIMEMACHINE_URL = "https://api.openweathermap.org/data/3.0/onecall/timemachine"
    }
}

/**
 * OpenWeatherMap の condition id → 共通の [WeatherCondition]。
 *
 * 出典：https://openweathermap.org/weather-conditions の "Weather condition codes"。
 * 先頭の桁がグループを表す（2xx=雷雨、3xx=霧雨、5xx=雨、6xx=雪、7xx=大気現象、8xx=晴れ/曇り）。
 *
 * | id | 意味 | 対応 |
 * |---|---|---|
 * | 200–232 | Thunderstorm | [WeatherCondition.THUNDER] |
 * | 300–321 | Drizzle | [WeatherCondition.RAIN] |
 * | 500–531 | Rain | [WeatherCondition.RAIN] |
 * | 600–622 | Snow | [WeatherCondition.SNOW] |
 * | 701–781 | Atmosphere（mist / smoke / haze / dust / fog / sand / ash / squall / tornado） | [WeatherCondition.FOG] |
 * | 800 | Clear | [WeatherCondition.CLEAR] |
 * | 801 | few clouds（11–25%） | [WeatherCondition.CLEAR] |
 * | 802–804 | scattered / broken / overcast clouds | [WeatherCondition.CLOUDY] |
 * | その他 | 表に無いid | [WeatherCondition.UNKNOWN] |
 *
 * 7xx をまとめて霧にしているのは、共通モデルに「砂塵」「竜巻」の枠が無いため。
 * どれも**視程が落ちる**という点で霧と同じ側に置くのが、変奏の使われ方
 * （見通しの悪い日の描写）に照らして最も近い。
 *
 * 801（雲量11–25%）を晴れ側に入れたのは、WMO の 1「おおむね晴れ」と揃えるため
 * （[weatherConditionFromWmoCode] の理由と同じ）。
 */
internal fun weatherConditionFromOwmConditionId(id: Int): WeatherCondition = when (id) {
    in 200..232 -> WeatherCondition.THUNDER
    in 300..321 -> WeatherCondition.RAIN
    in 500..531 -> WeatherCondition.RAIN
    in 600..622 -> WeatherCondition.SNOW
    in 701..781 -> WeatherCondition.FOG
    800, 801 -> WeatherCondition.CLEAR
    in 802..804 -> WeatherCondition.CLOUDY
    else -> WeatherCondition.UNKNOWN
}

/**
 * One Call 3.0 `timemachine` の応答（必要な部分だけ）。
 *
 * 指定時刻ぴったりの1件が `data` に入る（複数時刻を返す形式ではない）。
 */
@Serializable
private data class OpenWeatherMapResponse(
    val data: List<OpenWeatherMapEntry> = emptyList(),
)

@Serializable
private data class OpenWeatherMapEntry(
    /** `units=metric` を付けているので℃。 */
    val temp: Double? = null,
    val weather: List<OpenWeatherMapCondition> = emptyList(),
)

@Serializable
private data class OpenWeatherMapCondition(
    val id: Int,
)

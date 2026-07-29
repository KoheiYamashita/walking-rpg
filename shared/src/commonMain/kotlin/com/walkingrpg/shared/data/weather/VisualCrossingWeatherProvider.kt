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
 * Visual Crossing（https://www.visualcrossing.com/）の [WeatherProvider] 実装。**APIキーが要る**。
 *
 * Timeline Weather API に「地点／時刻」をパスで渡す。時刻は **epoch 秒**で送る：
 * Timeline API は `YYYY-MM-DDThh:mm:ss` 形式を**その地点のローカル時刻**として解釈するので、
 * 端末側でタイムゾーンを決めて文字列を組み立てると、旅行先の散歩で1日ずれる余地が残る。
 * epoch 秒ならその曖昧さが無い。
 *
 * `include=current` を付けると、指定時刻の実況が `currentConditions` に入る。
 * 応答の形は契約・パラメータで変わりうるので、`days[].hours[]` / `days[]` も
 * 順に見て拾えるようにしてある（[VisualCrossingResponse]）。
 *
 * キーはURLのクエリ（`key`）に乗る。例外にURLを出さない理由は [getWeatherJson] のKDoc。
 */
internal class VisualCrossingWeatherProvider(
    private val httpClient: HttpClient,
) : WeatherProvider {

    override val choice: WeatherProviderChoice = WeatherProviderChoice.VISUAL_CROSSING

    override suspend fun observe(query: WeatherQuery, apiKey: String): WeatherObservation {
        if (apiKey.isBlank()) missingApiKey(PROVIDER_NAME)

        val epochSeconds = query.timestampMs / 1000
        val url = "$TIMELINE_URL/${query.latitude},${query.longitude}/$epochSeconds"
        val body = httpClient.getWeatherJson(url, PROVIDER_NAME) {
            parameter("unitGroup", "metric")
            parameter("include", "current")
            parameter("elements", "datetime,temp,icon")
            parameter("contentType", "json")
            parameter("key", apiKey)
        }

        val conditions = weatherJson.decodeFromString<VisualCrossingResponse>(body).conditions()
            ?: missingValue(PROVIDER_NAME)
        val icon = conditions.icon ?: missingValue(PROVIDER_NAME)
        return WeatherObservation(
            condition = weatherConditionFromVisualCrossingIcon(icon),
            temperatureCelsius = conditions.temp,
        )
    }

    private companion object {
        const val PROVIDER_NAME = "Visual Crossing"
        const val TIMELINE_URL =
            "https://weather.visualcrossing.com/VisualCrossingWebServices/rest/services/timeline"
    }
}

/**
 * Visual Crossing の icon → 共通の [WeatherCondition]。
 *
 * 出典：https://www.visualcrossing.com/resources/documentation/weather-api/defining-icon-set-for-your-weather-data/
 * の icon set 2（既定）。
 *
 * | icon | 対応 |
 * |---|---|
 * | `snow`, `snow-showers-day`, `snow-showers-night` | [WeatherCondition.SNOW] |
 * | `thunder-rain`, `thunder-showers-day`, `thunder-showers-night` | [WeatherCondition.THUNDER] |
 * | `rain`, `showers-day`, `showers-night` | [WeatherCondition.RAIN] |
 * | `fog` | [WeatherCondition.FOG] |
 * | `cloudy`, `partly-cloudy-day`, `partly-cloudy-night` | [WeatherCondition.CLOUDY] |
 * | `clear-day`, `clear-night` | [WeatherCondition.CLEAR] |
 * | `wind` | [WeatherCondition.UNKNOWN] |
 * | その他 | [WeatherCondition.UNKNOWN] |
 *
 * `wind`（強風）だけ [WeatherCondition.UNKNOWN] に落としている。共通モデルに風の枠が無く、
 * かつ「風が強い」からといって晴れとも曇りとも決められないため。嘘の分類を1件入れるより、
 * 天候不明として変奏から外すほうが安全（architecture.md §8「欠測は天候不明として除外」）。
 *
 * `partly-cloudy-*`（雲量が中程度）を曇り側に置いたのは、WMO の 2「部分的に曇り」と揃えるため。
 * 晴れは `clear-*` だけ＝雲がほぼ無いとき、で3プロバイダの境目が揃う。
 */
internal fun weatherConditionFromVisualCrossingIcon(icon: String): WeatherCondition =
    when (icon.lowercase()) {
        "snow", "snow-showers-day", "snow-showers-night" -> WeatherCondition.SNOW
        "thunder-rain", "thunder-showers-day", "thunder-showers-night" -> WeatherCondition.THUNDER
        "rain", "showers-day", "showers-night" -> WeatherCondition.RAIN
        "fog" -> WeatherCondition.FOG
        "cloudy", "partly-cloudy-day", "partly-cloudy-night" -> WeatherCondition.CLOUDY
        "clear-day", "clear-night" -> WeatherCondition.CLEAR
        else -> WeatherCondition.UNKNOWN
    }

/**
 * Timeline API の応答（必要な部分だけ）。
 *
 * 指定時刻の実況は `include=current` を付けたときの `currentConditions` に入るが、
 * 応答に無いこともあるので `days[0].hours[0]` → `days[0]` の順に落とす。
 * 3段とも同じ形（`temp` / `icon`）なので1つの型で受けられる。
 */
@Serializable
private data class VisualCrossingResponse(
    val currentConditions: VisualCrossingConditions? = null,
    val days: List<VisualCrossingDay> = emptyList(),
) {
    fun conditions(): VisualCrossingConditions? {
        val day = days.firstOrNull()
        val candidates = listOfNotNull(currentConditions, day?.hours?.firstOrNull(), day?.conditions)
        // 天候が入っている段を選ぶ（`elements` の指定や契約によっては上の段が空で来る）
        return candidates.firstOrNull { it.icon != null } ?: candidates.firstOrNull()
    }
}

@Serializable
private data class VisualCrossingDay(
    val temp: Double? = null,
    val icon: String? = null,
    val hours: List<VisualCrossingConditions> = emptyList(),
) {
    /** 日単位の値も時間単位と同じ形で扱えるようにする。 */
    val conditions: VisualCrossingConditions get() = VisualCrossingConditions(temp = temp, icon = icon)
}

@Serializable
private data class VisualCrossingConditions(
    /** `unitGroup=metric` を付けているので℃。 */
    val temp: Double? = null,
    val icon: String? = null,
)

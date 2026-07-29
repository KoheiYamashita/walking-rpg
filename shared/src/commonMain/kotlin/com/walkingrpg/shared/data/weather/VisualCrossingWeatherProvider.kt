package com.walkingrpg.shared.data.weather

import com.walkingrpg.shared.domain.setup.WeatherProviderChoice
import com.walkingrpg.shared.domain.weather.WeatherCondition
import com.walkingrpg.shared.domain.weather.WeatherObservation
import com.walkingrpg.shared.domain.weather.WeatherProvider
import com.walkingrpg.shared.domain.weather.WeatherQuery
import io.ktor.client.HttpClient
import io.ktor.client.request.parameter
import kotlin.math.abs
import kotlinx.serialization.Serializable

/**
 * Visual Crossing（https://www.visualcrossing.com/）の [WeatherProvider] 実装。**APIキーが要る**。
 *
 * Timeline Weather API に「地点／時刻」をパスで渡す。時刻は **epoch 秒**で送る：
 * Timeline API は `YYYY-MM-DDThh:mm:ss` 形式を**その地点のローカル時刻**として解釈するので、
 * 端末側でタイムゾーンを決めて文字列を組み立てると、旅行先の散歩で1日ずれる余地が残る。
 * epoch 秒ならその曖昧さが無い。
 *
 * ## `include=hours` であって `current` ではない
 *
 * この用途で欲しいのは**散歩をした時刻**の天候で、「いま」ではない。
 * `include=current` を付けると応答の `currentConditions` に**呼び出し時点の実況**が入り、
 * 圏外だった散歩を後日リトライしたときに、その実況を散歩の天候として保存してしまう
 * （`session_weather` は行があれば二度と取り直さないので、間違いは訂正されない）。
 * そのため時間別（`days[].hours[]`）を要求し、[VisualCrossingResponse] でも
 * **時間別 → 日別 → `currentConditions`** の順に落とす。
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
            parameter("include", "hours")
            // datetimeEpoch は「どの時刻の値か」を突き合わせるために要る
            parameter("elements", "datetime,datetimeEpoch,temp,icon")
            parameter("contentType", "json")
            parameter("key", apiKey)
        }

        val conditions = weatherJson.decodeFromString<VisualCrossingResponse>(body)
            .conditionsAt(epochSeconds) ?: missingValue(PROVIDER_NAME)
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
 * Timeline API の応答（必要な部分だけ）。3段とも同じ形（`temp` / `icon`）なので
 * 1つの型（[VisualCrossingConditions]）で受けられる。
 */
@Serializable
private data class VisualCrossingResponse(
    val currentConditions: VisualCrossingConditions? = null,
    val days: List<VisualCrossingDay> = emptyList(),
) {
    /**
     * [epochSeconds]（＝散歩をした時刻）の天候を選ぶ。
     *
     * 優先順位は **時間別 → 日別 → `currentConditions`**。この順序に意味がある：
     * `currentConditions` は「応答を作った時点」の実況なので、圏外だった散歩を
     * 後日リトライしたときに使うと、散歩とは無関係な天候を確定値として保存してしまう。
     * 時間別・日別は問い合わせた時刻のもので、後日リトライしても同じ値が返る。
     * それでも最後の砦として残してあるのは、時間別も日別も無い応答で
     * 「取れなかった」にするより、近い値でも1件返すほうが実用的なため。
     */
    fun conditionsAt(epochSeconds: Long): VisualCrossingConditions? {
        val day = days.firstOrNull()
        val candidates = listOfNotNull(
            day?.hours?.closestTo(epochSeconds),
            day?.conditions,
            currentConditions,
        )
        // 天候が入っている段を選ぶ（`elements` の指定や契約によっては上の段が空で来る）
        return candidates.firstOrNull { it.icon != null } ?: candidates.firstOrNull()
    }
}

/**
 * 指定時刻に最も近い時間別の値。
 *
 * 時刻が分からない（`datetimeEpoch` が無い）応答では先頭に落とす。
 * 単一時刻の問い合わせなら、返ってくる時間別はその1時間ぶんだけなので先頭で合っている。
 */
private fun List<VisualCrossingConditions>.closestTo(
    epochSeconds: Long,
): VisualCrossingConditions? {
    if (isEmpty()) return null
    val dated = filter { it.datetimeEpoch != null }
    if (dated.isEmpty()) return first()
    return dated.minBy { hour -> abs(hour.datetimeEpoch!! - epochSeconds) }
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
    /** その値が何時のものか（epoch秒）。`elements` で要求しているが、無い応答も許す。 */
    val datetimeEpoch: Long? = null,
)

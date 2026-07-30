package com.walkingrpg.app.ui.review

import com.walkingrpg.shared.domain.growth.GrowthStage
import com.walkingrpg.shared.domain.review.ReviewGrownWay
import com.walkingrpg.shared.domain.weather.SessionWeather
import com.walkingrpg.shared.domain.weather.WeatherCondition
import kotlin.math.round

/** 振り返りの表示用の整形。UI都合の変換なのでUI層に置く（`HomeFormatting` と同じ扱い）。 */

/** 「2.3km」。散歩の距離に10m単位の情報量は無いので小数第1位まで。 */
internal fun formatDistanceKm(meters: Double): String {
    val scaled = round(meters / 100.0).toLong()
    return "${scaled / 10}.${scaled % 10}km"
}

/**
 * 「25分」「1時間5分」。
 *
 * 記録中の経過（`HomeFormatting.formatElapsed` の `mm:ss`）とは別の書式にしてある：
 * あちらは動いている数字、こちらは終わった散歩の長さで、秒の情報量が無い
 * （「おかえり」通知の本文と同じ書式）。
 */
internal fun formatWalkDuration(millis: Long): String {
    val totalMinutes = (millis / 60_000L).coerceAtLeast(0L)
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) "${hours}時間${minutes}分" else "${minutes}分"
}

/**
 * 天候の1行（「晴れ・18℃」）。
 *
 * 天候不明で確定した行（[WeatherCondition.UNKNOWN]）は `null`＝行を出さない。
 * 「不明」と書くくらいなら黙っている方が、1分で閉じる密度に合う。
 */
internal fun formatWeather(weather: SessionWeather?): String? {
    val known = weather?.takeIf { it.isKnown } ?: return null
    val temperature = known.temperatureCelsius?.let { "・${round(it).toInt()}℃" } ?: ""
    return "${known.condition.label()}$temperature"
}

/**
 * 育った道の1行（「いつもの道が 低木 → 木 に」）。
 *
 * 名前の無い道（大半の生活道路）は「名もない道」と言い換える：OSMの `name` が
 * 空であることは「その道に個性が無い」ことではないので、IDを出したりはしない。
 */
internal fun formatGrownWay(grown: ReviewGrownWay): String {
    val name = grown.name?.takeIf { it.isNotBlank() } ?: "名もない道"
    val from = grown.from
    return if (from == null) {
        "$name を、はじめて歩いた（${grown.to.label()}）"
    } else {
        "$name が ${from.label()} → ${grown.to.label()} に"
    }
}

/** 段階の呼び名（design.md §4.2「草 → 花 → 低木 → 木 → 生き物が住みつく」）。 */
internal fun GrowthStage.label(): String = when (this) {
    GrowthStage.GRASS -> "草"
    GrowthStage.FLOWER -> "花"
    GrowthStage.SHRUB -> "低木"
    GrowthStage.TREE -> "木"
    GrowthStage.CREATURE -> "生き物が住む道"
}

/** 天候の呼び名（design.md §8 の変奏の軸と同じ粒度）。 */
private fun WeatherCondition.label(): String = when (this) {
    WeatherCondition.CLEAR -> "晴れ"
    WeatherCondition.CLOUDY -> "曇り"
    WeatherCondition.RAIN -> "雨"
    WeatherCondition.SNOW -> "雪"
    WeatherCondition.FOG -> "霧"
    WeatherCondition.THUNDER -> "雷雨"
    // 出す前に落としてある（formatWeather）ので、ここには来ない
    WeatherCondition.UNKNOWN -> "天候不明"
}

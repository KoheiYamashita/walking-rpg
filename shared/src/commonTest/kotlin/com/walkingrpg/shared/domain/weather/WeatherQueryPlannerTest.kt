package com.walkingrpg.shared.domain.weather

import com.walkingrpg.shared.domain.walk.LocationSample
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 天候APIに送る「いつ・どこ」の決め方（[WeatherQueryPlanner]）。
 *
 * 見たいのは**自宅座標をそのまま外に出さない**こと（CONTRIBUTING.md「位置情報の扱い」）。
 * 座標は架空のもので、実在の地点は使わない。
 */
class WeatherQueryPlannerTest {

    /** 自宅（架空）から東へ出て戻る往復。最初と最後のサンプルが自宅前になる。 */
    private fun roundTripSamples(): List<LocationSample> {
        val offsets = listOf(0.0, 0.004, 0.008, 0.012, 0.008, 0.004, 0.0)
        return offsets.mapIndexed { index, offset ->
            LocationSample(
                sessionId = 1L,
                timestampMs = START_MS + index * 60_000L,
                latitude = HOME_LATITUDE,
                longitude = HOME_LONGITUDE + offset,
                accuracyMeters = 8.0,
            )
        }
    }

    @Test
    fun サンプルが無ければ問い合わせを組み立てない() {
        assertNull(WeatherQueryPlanner.plan(emptyList()))
    }

    @Test
    fun 代表点は中央のサンプルで自宅の目の前ではない() {
        val samples = roundTripSamples()

        val query = WeatherQueryPlanner.plan(samples)!!

        // 中央＝折り返し地点。最初・最後（自宅前）のサンプルではない
        assertEquals(samples[samples.size / 2].timestampMs, query.timestampMs)
        assertTrue(
            abs(query.longitude - HOME_LONGITUDE) > 0.005,
            "自宅の目の前の座標が出ている: $query",
        )
    }

    @Test
    fun 緯度経度は小数第2位に丸めて送る() {
        val samples = listOf(
            LocationSample(
                sessionId = 1L,
                timestampMs = START_MS,
                latitude = 12.345678,
                longitude = 34.561234,
                accuracyMeters = 5.0,
            ),
        )

        val query = WeatherQueryPlanner.plan(samples)!!

        assertEquals(12.35, query.latitude)
        assertEquals(34.56, query.longitude)
        // 時刻は丸めない（天候は時間単位で引くので、時刻はプロバイダ側が丸める）
        assertEquals(START_MS, query.timestampMs)
    }

    @Test
    fun 丸めは南半球西半球でも桁が増えない() {
        val samples = listOf(
            LocationSample(
                sessionId = 1L,
                timestampMs = START_MS,
                latitude = -12.345678,
                longitude = -34.567891,
                accuracyMeters = 5.0,
            ),
        )

        val query = WeatherQueryPlanner.plan(samples)!!

        assertEquals(-12.35, query.latitude)
        assertEquals(-34.57, query.longitude)
    }

    @Test
    fun サンプルが1件ならその点を使う() {
        val only = LocationSample(
            sessionId = 1L,
            timestampMs = START_MS,
            latitude = 1.0,
            longitude = 2.0,
            accuracyMeters = 5.0,
        )

        val query = WeatherQueryPlanner.plan(listOf(only))!!

        assertEquals(WeatherQuery(timestampMs = START_MS, latitude = 1.0, longitude = 2.0), query)
    }

    private companion object {
        /** 架空の自宅（実在の座標は使わない）。 */
        const val HOME_LATITUDE = 12.0
        const val HOME_LONGITUDE = 34.0
        const val START_MS = 1_700_000_000_000L
    }
}

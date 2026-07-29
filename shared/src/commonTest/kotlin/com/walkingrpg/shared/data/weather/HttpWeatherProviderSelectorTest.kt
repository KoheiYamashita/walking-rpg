package com.walkingrpg.shared.data.weather

import com.walkingrpg.shared.domain.setup.WeatherProviderChoice
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 設定の選択肢と実装の対応（issue #11「切り替えても動く」）。
 *
 * ここがずれると「Visual Crossing を選んだのに Open-Meteo に投げていた」という、
 * 動いてしまうぶん気付きにくい事故になる。
 */
class HttpWeatherProviderSelectorTest {

    private fun client() = weatherHttpClient(MockEngine { respond("{}") })

    private val selector = HttpWeatherProviderSelector(
        openMeteo = OpenMeteoWeatherProvider(client()),
        openWeatherMap = OpenWeatherMapWeatherProvider(client()),
        visualCrossing = VisualCrossingWeatherProvider(client()),
    )

    @Test
    fun 選択肢ごとに対応する実装を返す() {
        // 実装側が自分の選択肢を名乗っているので、突き合わせれば取り違えが分かる
        WeatherProviderChoice.entries.forEach { choice ->
            assertEquals(choice, selector.provider(choice).choice, choice.name)
        }
    }
}

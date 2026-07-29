package com.walkingrpg.shared.data.weather

import com.walkingrpg.shared.domain.setup.WeatherProviderChoice
import com.walkingrpg.shared.domain.weather.WeatherProvider
import com.walkingrpg.shared.domain.weather.WeatherProviderSelector

/**
 * 設定で選ばれたプロバイダを返す [WeatherProviderSelector]（design.md §9「プロバイダは差し替え可能」）。
 *
 * 3実装を具体型でコンストラクタに取るのは、DI（Koin）が同じ型の複数定義を
 * 区別できないため（同じ `WeatherProvider` として3つ登録すると、
 * どれが注入されるか分からない）。ここだけが3実装を名指しする場所になる。
 *
 * [WeatherProviderChoice] に対する `when` なので、プロバイダを増やしたら
 * **コンパイルエラーで**分岐の追加漏れに気付ける（実行時に既定へ落ちて
 * 「選んだはずのプロバイダが使われない」より早い）。
 */
internal class HttpWeatherProviderSelector(
    private val openMeteo: OpenMeteoWeatherProvider,
    private val openWeatherMap: OpenWeatherMapWeatherProvider,
    private val visualCrossing: VisualCrossingWeatherProvider,
) : WeatherProviderSelector {

    override fun provider(choice: WeatherProviderChoice): WeatherProvider = when (choice) {
        WeatherProviderChoice.OPEN_METEO -> openMeteo
        WeatherProviderChoice.OPEN_WEATHER_MAP -> openWeatherMap
        WeatherProviderChoice.VISUAL_CROSSING -> visualCrossing
    }
}

package com.walkingrpg.shared.domain.weather

import com.walkingrpg.shared.domain.setup.HomeAnchor
import com.walkingrpg.shared.domain.setup.LlmConnectionSettings
import com.walkingrpg.shared.domain.setup.SetupRepository
import com.walkingrpg.shared.domain.setup.WeatherProviderChoice
import com.walkingrpg.shared.domain.setup.WeatherSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.yield

/**
 * 天候の後付け取得まわりのテスト用差し替え。
 *
 * HTTP実装（3プロバイダ）は `data/weather` 側のテストが MockEngine で見ているので、
 * ここでは「UseCaseが何をどの順で読み書きするか」だけを見られる最小の形にする。
 */

/** `session_weather` のインメモリ版。行の有無が「未取得／確定」の区別そのもの。 */
internal class FakeSessionWeatherRepository(
    private val rows: MutableMap<Long, SessionWeather> = mutableMapOf(),
    /** 終了済みセッションのID（古い順）。「未取得」はここから行のあるぶんを引いた残り。 */
    var finishedSessionIds: List<Long> = emptyList(),
) : SessionWeatherRepository {

    /** `save` の呼び出し履歴（置き換えが何回起きたかを見たいので列で持つ）。 */
    val saved = mutableListOf<SessionWeather>()

    override suspend fun save(weather: SessionWeather) {
        saved += weather
        rows[weather.sessionId] = weather
        revision.value++
    }

    override suspend fun weather(sessionId: Long): SessionWeather? = rows[sessionId]

    // 保存のたびに流し直す（実装のSQLDelight購読に相当する動き）
    override fun observe(sessionId: Long): Flow<SessionWeather?> = revision.map { rows[sessionId] }

    override suspend fun sessionIdsWithoutWeather(): List<Long> =
        finishedSessionIds.filterNot { it in rows }

    private val revision = MutableStateFlow(0)

    fun rows(): Map<Long, SessionWeather> = rows.toMap()
}

/** 呼ばれた回数と問い合わせ内容を覚えるプロバイダ。 */
internal class FakeWeatherProvider(
    override val choice: WeatherProviderChoice = WeatherProviderChoice.OPEN_METEO,
    private val observation: WeatherObservation = WeatherObservation(WeatherCondition.CLEAR, 20.0),
    /** 非nullなら毎回これを投げる（圏外・API障害の再現）。 */
    private val failure: Throwable? = null,
    /**
     * 問い合わせの途中で必ず1回中断する（通信は待たされるもの、の再現）。
     * 並行実行の検証で、他のコルーチンに割り込む隙を作るために使う。
     */
    private val suspendWhileFetching: Boolean = false,
) : WeatherProvider {

    val queries = mutableListOf<WeatherQuery>()
    val apiKeys = mutableListOf<String>()

    override suspend fun observe(query: WeatherQuery, apiKey: String): WeatherObservation {
        queries += query
        apiKeys += apiKey
        if (suspendWhileFetching) yield()
        failure?.let { throw it }
        return observation
    }
}

/** どの選択肢でも同じ実装に落とす差し替え（振り分けの検証は選択肢ごとに1個ずつ渡す）。 */
internal class FakeWeatherProviderSelector(
    private val providers: Map<WeatherProviderChoice, WeatherProvider>,
) : WeatherProviderSelector {

    constructor(provider: WeatherProvider) : this(
        WeatherProviderChoice.entries.associateWith { provider },
    )

    override fun provider(choice: WeatherProviderChoice): WeatherProvider =
        providers.getValue(choice)
}

/** 天候設定だけを持つ [SetupRepository]。他は使わない前提で落とす。 */
internal class WeatherSettingsSetupRepository(
    private val settings: WeatherSettings = WeatherSettings(),
) : SetupRepository {

    var loadCount = 0
        private set

    override suspend fun loadWeatherSettings(): WeatherSettings {
        loadCount++
        return settings
    }

    override suspend fun loadLlmConnection(): LlmConnectionSettings? = notUsed()
    override suspend fun saveLlmConnection(settings: LlmConnectionSettings) = notUsed()
    override suspend fun saveWeatherSettings(settings: WeatherSettings) = notUsed()
    override suspend fun loadHomeAnchor(): HomeAnchor? = notUsed()
    override suspend fun saveHomeAnchor(anchor: HomeAnchor) = notUsed()
    override suspend fun isSetupCompleted(): Boolean = notUsed()
    override suspend fun markSetupCompleted() = notUsed()

    private fun notUsed(): Nothing = error("天候の取り込みでは使わない")
}

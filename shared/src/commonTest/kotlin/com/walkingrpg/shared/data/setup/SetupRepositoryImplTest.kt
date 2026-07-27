package com.walkingrpg.shared.data.setup

import com.walkingrpg.shared.data.SetupRepositoryImpl
import com.walkingrpg.shared.domain.setup.HomeAnchor
import com.walkingrpg.shared.domain.setup.LlmConnectionSettings
import com.walkingrpg.shared.domain.setup.LlmFormat
import com.walkingrpg.shared.domain.setup.WeatherProviderChoice
import com.walkingrpg.shared.domain.setup.WeatherSettings
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 保存先の振り分けの検証。
 *
 * **秘密（APIキー・自宅座標）がセキュアストレージ側に入り、
 * 非秘密の設定ストアには漏れていない**ことがこのテストの主眼。
 * 座標は架空の値を使う（CONTRIBUTING.md「実在の座標を書かない」）。
 */
class SetupRepositoryImplTest {

    private val secureStorage = FakeSecureStorage()
    private val appSettings = FakeAppSettings()
    private val repository = SetupRepositoryImpl(secureStorage, appSettings)

    private val llmSettings = LlmConnectionSettings(
        format = LlmFormat.ANTHROPIC,
        baseUrl = "https://example.invalid",
        model = "test-model",
        apiKey = "dummy-key-for-test",
    )

    @Test
    fun LLM設定は往復できる() = runTest {
        assertNull(repository.loadLlmConnection())

        repository.saveLlmConnection(llmSettings)

        assertEquals(llmSettings, repository.loadLlmConnection())
    }

    @Test
    fun APIキーはセキュアストレージにだけ入る() = runTest {
        repository.saveLlmConnection(llmSettings)

        assertTrue(secureStorage.snapshot().containsValue("dummy-key-for-test"))
        assertFalse(
            appSettings.snapshot().values.any { it == "dummy-key-for-test" },
            "APIキーが非暗号化の設定ストアに漏れている",
        )
    }

    @Test
    fun キーだけ消えていたら未設定として扱う() = runTest {
        repository.saveLlmConnection(llmSettings)
        // Keystoreの鍵が失われて復号できなくなったケースの再現
        secureStorage.snapshot().keys.forEach { secureStorage.remove(it) }

        assertNull(repository.loadLlmConnection())
    }

    @Test
    fun 天候は未設定なら既定のOpenMeteo() = runTest {
        assertEquals(
            WeatherSettings(WeatherProviderChoice.OPEN_METEO, apiKey = ""),
            repository.loadWeatherSettings(),
        )
    }

    @Test
    fun 天候のキーもセキュアストレージに入る() = runTest {
        val settings = WeatherSettings(
            provider = WeatherProviderChoice.OPEN_WEATHER_MAP,
            apiKey = "dummy-weather-key",
        )

        repository.saveWeatherSettings(settings)

        assertEquals(settings, repository.loadWeatherSettings())
        assertTrue(secureStorage.snapshot().containsValue("dummy-weather-key"))
        assertFalse(appSettings.snapshot().values.any { it == "dummy-weather-key" })
    }

    @Test
    fun キー不要のプロバイダに戻すと保存済みのキーを消す() = runTest {
        repository.saveWeatherSettings(
            WeatherSettings(WeatherProviderChoice.VISUAL_CROSSING, "dummy-weather-key"),
        )

        repository.saveWeatherSettings(WeatherSettings(WeatherProviderChoice.OPEN_METEO, ""))

        assertFalse(secureStorage.snapshot().containsValue("dummy-weather-key"))
        assertEquals("", repository.loadWeatherSettings().apiKey)
    }

    @Test
    fun 自宅は往復でき座標は設定ストアに出ない() = runTest {
        // 架空座標
        val anchor = HomeAnchor(latitude = 12.5, longitude = 34.25, blurRadiusMeters = 300)

        repository.saveHomeAnchor(anchor)

        assertEquals(anchor, repository.loadHomeAnchor())
        assertFalse(
            appSettings.snapshot().values.any { it.toString().contains("12.5") },
            "自宅座標が非暗号化の設定ストアに漏れている",
        )
    }

    @Test
    fun 自宅は未登録ならnull() = runTest {
        assertNull(repository.loadHomeAnchor())
    }

    @Test
    fun 完了フラグは初期状態でfalse() = runTest {
        assertFalse(repository.isSetupCompleted())

        repository.markSetupCompleted()

        assertTrue(repository.isSetupCompleted())
    }
}

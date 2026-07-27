package com.walkingrpg.shared.domain.setup

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 入力バリデーションの検証。実在のAPIキーはテストにも書かない（架空の文字列を使う）。 */
class SetupValidationTest {

    private val valid = LlmConnectionSettings(
        format = LlmFormat.ANTHROPIC,
        baseUrl = "https://example.invalid",
        model = "test-model",
        apiKey = "dummy-key-for-test",
    )

    @Test
    fun 正しい入力はエラーなし() {
        assertTrue(LlmConnectionValidator.validate(valid).isEmpty())
    }

    @Test
    fun ベースURLは空でもスキームなしでも弾く() {
        assertContains(
            LlmConnectionValidator.validate(valid.copy(baseUrl = "  ")),
            LlmSettingsError.BLANK_BASE_URL,
        )
        assertContains(
            LlmConnectionValidator.validate(valid.copy(baseUrl = "example.invalid")),
            LlmSettingsError.INVALID_BASE_URL,
        )
    }

    @Test
    fun モデル名とAPIキーの未入力を弾く() {
        assertContains(
            LlmConnectionValidator.validate(valid.copy(model = " ")),
            LlmSettingsError.BLANK_MODEL,
        )
        assertContains(
            LlmConnectionValidator.validate(valid.copy(apiKey = "")),
            LlmSettingsError.BLANK_API_KEY,
        )
    }

    @Test
    fun APIキーの途中に空白があれば貼り付け事故として弾く() {
        assertContains(
            LlmConnectionValidator.validate(valid.copy(apiKey = "dummy key")),
            LlmSettingsError.API_KEY_HAS_WHITESPACE,
        )
    }

    @Test
    fun 正規化で前後の空白と末尾スラッシュを落とす() {
        val normalized = LlmConnectionValidator.normalize(
            valid.copy(
                baseUrl = "  https://example.invalid/  ",
                model = " test-model ",
                apiKey = "dummy-key-for-test\n",
            ),
        )

        assertEquals("https://example.invalid", normalized.baseUrl)
        assertEquals("test-model", normalized.model)
        assertEquals("dummy-key-for-test", normalized.apiKey)
        // 末尾改行つきのキーは正規化すれば通る（貼り付け事故の救済）
        assertTrue(LlmConnectionValidator.validate(normalized).isEmpty())
    }

    @Test
    fun フォーマット既定値はそのまま使える形になっている() {
        LlmFormat.entries.forEach { format ->
            assertTrue(
                format.defaultBaseUrl.startsWith("https://"),
                "${format.name} のベースURL既定値がhttpsでない",
            )
        }
        assertEquals("claude-haiku-4-5-20251001", LlmFormat.ANTHROPIC.defaultModel)
        // OpenAI互換は接続先ごとにモデル名が違うので空のまま入力させる
        assertEquals("", LlmFormat.OPENAI.defaultModel)
    }

    @Test
    fun 天候はキー不要のプロバイダなら常にOK() {
        val settings = WeatherSettings(WeatherProviderChoice.OPEN_METEO, apiKey = "")
        assertTrue(WeatherSettingsValidator.isReady(settings))
    }

    @Test
    fun 天候はキーが要るプロバイダで未入力なら進めない() {
        val settings = WeatherSettings(WeatherProviderChoice.OPEN_WEATHER_MAP, apiKey = "")
        assertContains(
            WeatherSettingsValidator.validate(settings),
            WeatherSettingsError.BLANK_API_KEY,
        )
    }

    @Test
    fun 天候はキー不要に戻したらキーを持ち回らない() {
        val normalized = WeatherSettingsValidator.normalize(
            WeatherSettings(WeatherProviderChoice.OPEN_METEO, apiKey = "dummy-key-for-test"),
        )
        assertEquals("", normalized.apiKey)
    }
}

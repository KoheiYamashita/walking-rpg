package com.walkingrpg.shared.domain.setup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 秘密を持つモデルの `toString()` の検証。
 *
 * data class の既定実装は全フィールドをそのまま出すので、ログ・例外メッセージに
 * APIキーや自宅座標が載ってしまう。KDocの約束（design.md §9・CONTRIBUTING.md）を
 * 実装でも守れているかをここで固定する。
 */
class SetupModelsTest {

    private val llm = LlmConnectionSettings(
        format = LlmFormat.ANTHROPIC,
        baseUrl = "https://example.invalid",
        model = "test-model",
        apiKey = "dummy-key-for-test",
    )

    // 架空座標（CONTRIBUTING.md「実在の座標を書かない」）
    private val home = HomeAnchor(latitude = 12.5, longitude = 34.25, blurRadiusMeters = 300)

    @Test
    fun LLM設定のtoStringにAPIキーは出ない() {
        val text = llm.toString()

        assertFalse("dummy-key-for-test" in text, text)
        assertTrue("apiKey=***" in text, text)
        // 秘密でない項目は切り分けに要るので残す
        assertTrue("https://example.invalid" in text, text)
        assertTrue("test-model" in text, text)
    }

    @Test
    fun 天候設定のtoStringにAPIキーは出ない() {
        val text = WeatherSettings(
            provider = WeatherProviderChoice.OPEN_WEATHER_MAP,
            apiKey = "dummy-weather-key",
        ).toString()

        assertFalse("dummy-weather-key" in text, text)
        assertTrue("apiKey=***" in text, text)
    }

    @Test
    fun 自宅のtoStringに座標は出ない() {
        val text = home.toString()

        assertFalse("12.5" in text, text)
        assertFalse("34.25" in text, text)
        // 秘密ではないぼかし半径は出してよい
        assertTrue("300" in text, text)
    }

    @Test
    fun 設定スナップショットごと出しても秘密は漏れない() {
        val text = SavedSetupSettings(
            llm = llm,
            weather = WeatherSettings(WeatherProviderChoice.OPEN_WEATHER_MAP, "dummy-weather-key"),
            home = home,
        ).toString()

        assertFalse("dummy-key-for-test" in text, text)
        assertFalse("dummy-weather-key" in text, text)
        assertFalse("12.5" in text, text)
    }

    @Test
    fun マスクしてもequalsとhashCodeは値で比べる() {
        // toString だけを差し替えていること（data class の等価性は壊さない）の確認
        assertEquals(llm, llm.copy())
        assertEquals(llm.hashCode(), llm.copy().hashCode())
        assertFalse(llm == llm.copy(apiKey = "other-key"))
    }
}

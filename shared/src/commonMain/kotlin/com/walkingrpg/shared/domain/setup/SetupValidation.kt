package com.walkingrpg.shared.domain.setup

/**
 * 入力バリデーション（純関数）。
 *
 * 「通信する前に弾ける間違い」だけをここで見る。キーが正しいかどうかは
 * 疎通テスト（[LlmConnectionTester]）の仕事で、こちらでは判定しない。
 */

/** LLM接続設定の入力エラー。文言はそのままUIに出す。 */
enum class LlmSettingsError(val message: String) {
    BLANK_BASE_URL("ベースURLを入力してください。"),
    INVALID_BASE_URL("ベースURLは http:// または https:// で始めてください。"),
    BLANK_MODEL("モデル名を入力してください。"),
    BLANK_API_KEY("APIキーを入力してください。"),
    API_KEY_HAS_WHITESPACE("APIキーに空白が含まれています。貼り付け時の改行や空白を取り除いてください。"),
}

object LlmConnectionValidator {

    /**
     * 入力を検証する。空なら問題なし。
     *
     * ベースURLの末尾スラッシュは許容する（パス連結側で正規化する）。
     */
    fun validate(settings: LlmConnectionSettings): List<LlmSettingsError> = buildList {
        val baseUrl = settings.baseUrl.trim()
        when {
            baseUrl.isEmpty() -> add(LlmSettingsError.BLANK_BASE_URL)
            !baseUrl.startsWith("http://") && !baseUrl.startsWith("https://") ->
                add(LlmSettingsError.INVALID_BASE_URL)
        }

        if (settings.model.trim().isEmpty()) add(LlmSettingsError.BLANK_MODEL)

        val apiKey = settings.apiKey
        when {
            apiKey.isBlank() -> add(LlmSettingsError.BLANK_API_KEY)
            apiKey.any { it.isWhitespace() } -> add(LlmSettingsError.API_KEY_HAS_WHITESPACE)
        }
    }

    /** 前後の空白を落とした保存用の設定。APIキーの貼り付け事故（末尾改行）を吸収する。 */
    fun normalize(settings: LlmConnectionSettings): LlmConnectionSettings = settings.copy(
        baseUrl = settings.baseUrl.trim().trimEnd('/'),
        model = settings.model.trim(),
        apiKey = settings.apiKey.trim(),
    )
}

/** 天候プロバイダのキー入力エラー。 */
enum class WeatherSettingsError(val message: String) {
    BLANK_API_KEY("選択したプロバイダにはAPIキーが必要です。"),
    API_KEY_HAS_WHITESPACE("APIキーに空白が含まれています。"),
}

object WeatherSettingsValidator {

    /**
     * 形式チェック程度の検証。キーが有効かは #11 の Provider 実装が実際に叩いて確かめる。
     * キー不要のプロバイダ（Open-Meteo）ならキー欄は無視する。
     */
    fun validate(settings: WeatherSettings): List<WeatherSettingsError> = buildList {
        if (!settings.provider.requiresApiKey) return@buildList
        val apiKey = settings.apiKey
        when {
            apiKey.isBlank() -> add(WeatherSettingsError.BLANK_API_KEY)
            apiKey.any { it.isWhitespace() } -> add(WeatherSettingsError.API_KEY_HAS_WHITESPACE)
        }
    }

    /** 先へ進んでよいか（[SetupProgress.weatherReady] の元になる判定）。 */
    fun isReady(settings: WeatherSettings): Boolean = validate(settings).isEmpty()

    /** キー不要のプロバイダに切り替えたらキーは持ち回らない（残しておく理由がない）。 */
    fun normalize(settings: WeatherSettings): WeatherSettings =
        if (settings.provider.requiresApiKey) {
            settings.copy(apiKey = settings.apiKey.trim())
        } else {
            settings.copy(apiKey = "")
        }
}

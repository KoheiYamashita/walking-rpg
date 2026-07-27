package com.walkingrpg.shared.domain.setup

/**
 * 初回セットアップのドメインモデル（issue #6・design.md §9・architecture.md §5）。
 *
 * 純Kotlin。Keystore / Keychain も SharedPreferences も Ktor もここには現れない
 * （保存と通信はデータ層・プラットフォーム層の仕事）。
 */

/**
 * LLMのリクエスト形式（design.md §9「AnthropicとOpenAIの2フォーマットのみ実装」）。
 *
 * ベースURL・モデル名は自由設定なので、ここに持つのは「選択時の既定値」だけ。
 * OpenAI互換エンドポイント（OpenRouter・ローカルLLM等）は OPENAI を選んで
 * ベースURLを差し替えれば使える。
 *
 * @param defaultBaseUrl フォーマットを選んだときに入力欄へ自動で入る値。
 * @param defaultModel 同上。OpenAI互換は接続先ごとにモデル名が違うので空にしておく。
 */
enum class LlmFormat(
    val displayName: String,
    val defaultBaseUrl: String,
    val defaultModel: String,
) {
    ANTHROPIC(
        displayName = "Anthropic Messages",
        defaultBaseUrl = "https://api.anthropic.com",
        defaultModel = "claude-haiku-4-5-20251001",
    ),
    OPENAI(
        displayName = "OpenAI Chat Completions",
        defaultBaseUrl = "https://api.openai.com/v1",
        defaultModel = "",
    ),
}

/**
 * LLM接続設定1件。
 *
 * [apiKey] は秘密なので、データ層でセキュアストレージ（Keystore / Keychain）に分けて
 * 保存する。ログ・エクスポートには絶対に出さない（design.md §9）。
 */
data class LlmConnectionSettings(
    val format: LlmFormat,
    val baseUrl: String,
    val model: String,
    val apiKey: String,
)

/** 天候プロバイダの選択肢（architecture.md §1）。Provider実装そのものは #11 の領分。 */
enum class WeatherProviderChoice(
    val displayName: String,
    val requiresApiKey: Boolean,
) {
    OPEN_METEO(displayName = "Open-Meteo（キー不要）", requiresApiKey = false),
    OPEN_WEATHER_MAP(displayName = "OpenWeatherMap", requiresApiKey = true),
    VISUAL_CROSSING(displayName = "Visual Crossing", requiresApiKey = true),
}

/** 天候の設定。既定はキー不要の Open-Meteo なので、何もしなくても先へ進める。 */
data class WeatherSettings(
    val provider: WeatherProviderChoice = WeatherProviderChoice.OPEN_METEO,
    val apiKey: String = "",
)

/**
 * 自宅位置とぼかし半径。
 *
 * **この座標は端末内にしか出さない。** ログ・エクスポート・リポジトリのいずれにも
 * 流さないこと（CONTRIBUTING.md「位置情報の扱い」）。保存先もセキュアストレージにして、
 * OSバックアップに乗らないようにしてある。
 *
 * @param blurRadiusMeters 自宅周辺で軌跡をぼかす半径。表示・共有時にこの円内を伏せる用途。
 */
data class HomeAnchor(
    val latitude: Double,
    val longitude: Double,
    val blurRadiusMeters: Int,
)

/** ぼかし半径の選択肢（UIのラジオボタンと同じ並び）。 */
val HOME_BLUR_RADIUS_CHOICES: List<Int> = listOf(100, 200, 300)

/** ぼかし半径の既定値。 */
const val DEFAULT_HOME_BLUR_RADIUS_METERS: Int = 200

/** セットアップウィザードのステップ。 */
enum class SetupStep {
    /** ようこそ。何をするのか説明するだけ。 */
    WELCOME,

    /** LLM接続設定。疎通が通るまで先へ進めない（決定事項）。 */
    LLM,

    /** 天候プロバイダ選択。既定のOpen-Meteoはキー不要なのでそのまま進める。 */
    WEATHER,

    /** 位置情報の権限と自宅登録。自宅は後から設定してもよい。 */
    HOME,

    /** 対象圏のOSM取り込み（#5）。 */
    AREA,

    /** 完了。 */
    DONE,
}

/**
 * セットアップの進捗。ウィザードが「次へ進めるか」を判断する材料。
 *
 * @param llmVerified 疎通テストが通ったか。
 * @param weatherReady 天候の設定が矛盾していないか（キーが要るのに未入力でない）。
 * @param homeRegistered 自宅を登録したか。任意なので進行は止めない。
 * @param areaImported 対象圏の取り込みが成功したか。
 */
data class SetupProgress(
    val llmVerified: Boolean = false,
    val weatherReady: Boolean = true,
    val homeRegistered: Boolean = false,
    val areaImported: Boolean = false,
)

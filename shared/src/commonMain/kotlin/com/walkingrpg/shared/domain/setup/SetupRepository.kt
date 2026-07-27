package com.walkingrpg.shared.domain.setup

/**
 * セットアップで決めた設定の永続化境界（architecture.md §2）。
 *
 * 「どこに置くか」はデータ層に閉じる。実装では
 * - 秘密（APIキー・自宅座標）→ セキュアストレージ（Keystore / Keychain。OSバックアップ対象外）
 * - 非秘密（フォーマット・URL・モデル名・プロバイダ選択・完了フラグ）→ 通常の設定保存
 * に振り分ける。ドメイン層はその区別を知らない。
 *
 * セットアップ後の設定変更は issue #20（設定画面）が同じインターフェースを使う想定。
 */
interface SetupRepository {

    /** 保存済みのLLM接続設定。未設定なら `null`。 */
    suspend fun loadLlmConnection(): LlmConnectionSettings?

    suspend fun saveLlmConnection(settings: LlmConnectionSettings)

    /** 保存済みの天候設定。未設定なら既定（Open-Meteo）。 */
    suspend fun loadWeatherSettings(): WeatherSettings

    suspend fun saveWeatherSettings(settings: WeatherSettings)

    /** 登録済みの自宅。未登録なら `null`。**端末内でしか読まない値**。 */
    suspend fun loadHomeAnchor(): HomeAnchor?

    suspend fun saveHomeAnchor(anchor: HomeAnchor)

    /** セットアップ完了フラグ。false の間はホームに入れない。 */
    suspend fun isSetupCompleted(): Boolean

    suspend fun markSetupCompleted()
}

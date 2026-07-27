package com.walkingrpg.shared.data

import com.walkingrpg.shared.domain.setup.DEFAULT_HOME_BLUR_RADIUS_METERS
import com.walkingrpg.shared.domain.setup.HomeAnchor
import com.walkingrpg.shared.domain.setup.LlmConnectionSettings
import com.walkingrpg.shared.domain.setup.LlmFormat
import com.walkingrpg.shared.domain.setup.SetupRepository
import com.walkingrpg.shared.domain.setup.WeatherProviderChoice
import com.walkingrpg.shared.domain.setup.WeatherSettings
import com.walkingrpg.shared.platform.AppSettings
import com.walkingrpg.shared.platform.SecureStorage

/**
 * [SetupRepository] の実装。
 *
 * **秘密と非秘密で保存先を分ける**のがこのクラスの主な仕事：
 *
 * | 値 | 保存先 | 理由 |
 * |---|---|---|
 * | APIキー（LLM・天候） | [SecureStorage] | design.md §9「端末のセキュアストレージに保存」 |
 * | 自宅座標・ぼかし半径 | [SecureStorage] | 端末内限定。OSバックアップにも乗せない |
 * | フォーマット・ベースURL・モデル名 | [AppSettings] | 秘密ではない。再入力の手間を減らすため移行はしてよい |
 * | 天候プロバイダ選択・完了フラグ | [AppSettings] | 同上 |
 *
 * 自宅座標は**このクラスの外へは [HomeAnchor] としてしか出さない**。
 * ログ出力・エクスポート・ネットワーク送信の経路をここから生やさないこと
 * （CONTRIBUTING.md「位置情報の扱い」）。
 */
internal class SetupRepositoryImpl(
    private val secureStorage: SecureStorage,
    private val appSettings: AppSettings,
) : SetupRepository {

    override suspend fun loadLlmConnection(): LlmConnectionSettings? {
        val format = appSettings.getString(KEY_LLM_FORMAT)
            ?.let { stored -> LlmFormat.entries.firstOrNull { it.name == stored } }
            ?: return null
        val baseUrl = appSettings.getString(KEY_LLM_BASE_URL) ?: format.defaultBaseUrl
        val model = appSettings.getString(KEY_LLM_MODEL) ?: format.defaultModel
        val apiKey = secureStorage.get(KEY_LLM_API_KEY) ?: return null

        return LlmConnectionSettings(
            format = format,
            baseUrl = baseUrl,
            model = model,
            apiKey = apiKey,
        )
    }

    override suspend fun saveLlmConnection(settings: LlmConnectionSettings) {
        appSettings.putString(KEY_LLM_FORMAT, settings.format.name)
        appSettings.putString(KEY_LLM_BASE_URL, settings.baseUrl)
        appSettings.putString(KEY_LLM_MODEL, settings.model)
        secureStorage.put(KEY_LLM_API_KEY, settings.apiKey)
    }

    override suspend fun loadWeatherSettings(): WeatherSettings {
        val provider = appSettings.getString(KEY_WEATHER_PROVIDER)
            ?.let { stored -> WeatherProviderChoice.entries.firstOrNull { it.name == stored } }
            ?: WeatherProviderChoice.OPEN_METEO
        val apiKey = if (provider.requiresApiKey) {
            secureStorage.get(KEY_WEATHER_API_KEY).orEmpty()
        } else {
            ""
        }
        return WeatherSettings(provider = provider, apiKey = apiKey)
    }

    override suspend fun saveWeatherSettings(settings: WeatherSettings) {
        appSettings.putString(KEY_WEATHER_PROVIDER, settings.provider.name)
        if (settings.provider.requiresApiKey) {
            secureStorage.put(KEY_WEATHER_API_KEY, settings.apiKey)
        } else {
            // キー不要のプロバイダに戻したなら、残しておく理由がない
            secureStorage.remove(KEY_WEATHER_API_KEY)
        }
    }

    override suspend fun loadHomeAnchor(): HomeAnchor? {
        val latitude = secureStorage.get(KEY_HOME_LATITUDE)?.toDoubleOrNull() ?: return null
        val longitude = secureStorage.get(KEY_HOME_LONGITUDE)?.toDoubleOrNull() ?: return null
        val radius = secureStorage.get(KEY_HOME_BLUR_RADIUS)?.toIntOrNull()
            ?: DEFAULT_HOME_BLUR_RADIUS_METERS
        return HomeAnchor(latitude = latitude, longitude = longitude, blurRadiusMeters = radius)
    }

    override suspend fun saveHomeAnchor(anchor: HomeAnchor) {
        secureStorage.put(KEY_HOME_LATITUDE, anchor.latitude.toString())
        secureStorage.put(KEY_HOME_LONGITUDE, anchor.longitude.toString())
        secureStorage.put(KEY_HOME_BLUR_RADIUS, anchor.blurRadiusMeters.toString())
    }

    /**
     * 完了フラグ（[AppSettings]）だけでなく、**LLMのAPIキーが実在するか**も見る。
     *
     * [AppSettings] はOSバックアップの対象なので、機種変更・クラウド復元では
     * 完了フラグだけが戻り、[SecureStorage] 側のキーは設計どおり戻らない。
     * フラグだけを信じるとキーが無いままホームに入れてしまい、
     * 「疎通が通るまでプレイを開始できない」（design.md §9）が破れる。
     *
     * キーが無い＝未完了として扱えば、ウィザードが保存済みの
     * フォーマット・ベースURL・モデル名を読み直した状態で再開するので、
     * ユーザーはAPIキーの再入力と疎通テストだけで復帰できる。
     */
    override suspend fun isSetupCompleted(): Boolean =
        appSettings.getBoolean(KEY_SETUP_COMPLETED, false) &&
            secureStorage.get(KEY_LLM_API_KEY) != null

    override suspend fun markSetupCompleted() {
        appSettings.putBoolean(KEY_SETUP_COMPLETED, true)
    }

    private companion object {
        const val KEY_LLM_FORMAT = "llm.format"
        const val KEY_LLM_BASE_URL = "llm.baseUrl"
        const val KEY_LLM_MODEL = "llm.model"
        const val KEY_LLM_API_KEY = "llm.apiKey"
        const val KEY_WEATHER_PROVIDER = "weather.provider"
        const val KEY_WEATHER_API_KEY = "weather.apiKey"
        const val KEY_HOME_LATITUDE = "home.latitude"
        const val KEY_HOME_LONGITUDE = "home.longitude"
        const val KEY_HOME_BLUR_RADIUS = "home.blurRadiusMeters"
        const val KEY_SETUP_COMPLETED = "setup.completed"
    }
}

package com.walkingrpg.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.walkingrpg.shared.domain.backup.ExportBackupResult
import com.walkingrpg.shared.domain.backup.ExportBackupUseCase
import com.walkingrpg.shared.domain.backup.ImportBackupResult
import com.walkingrpg.shared.domain.backup.ImportBackupUseCase
import com.walkingrpg.shared.domain.osm.GetOsmMasterCountsUseCase
import com.walkingrpg.shared.domain.osm.OsmImportResult
import com.walkingrpg.shared.domain.osm.OsmMasterCounts
import com.walkingrpg.shared.domain.osm.ReimportOsmAreaUseCase
import com.walkingrpg.shared.domain.setup.DEFAULT_HOME_BLUR_RADIUS_METERS
import com.walkingrpg.shared.domain.setup.LlmConnectionSettings
import com.walkingrpg.shared.domain.setup.LlmConnectionTestResult
import com.walkingrpg.shared.domain.setup.LlmFormat
import com.walkingrpg.shared.domain.setup.LoadSetupSettingsUseCase
import com.walkingrpg.shared.domain.setup.RegisterHomeAnchorUseCase
import com.walkingrpg.shared.domain.setup.SaveWeatherSettingsUseCase
import com.walkingrpg.shared.domain.setup.TestLlmConnectionUseCase
import com.walkingrpg.shared.domain.setup.UpdateHomeBlurRadiusUseCase
import com.walkingrpg.shared.domain.setup.WeatherProviderChoice
import com.walkingrpg.shared.domain.setup.WeatherSettings
import com.walkingrpg.shared.domain.walk.LocationPermissionStatus
import com.walkingrpg.shared.domain.walk.ObserveLocationPermissionUseCase
import com.walkingrpg.shared.domain.walk.RefreshLocationPermissionUseCase
import com.walkingrpg.shared.domain.walk.RequestLocationPermissionUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 設定画面の状態（issue #20・design.md §9）。
 *
 * APIキーは編集フォームに出す都合でここに載るが、**ログ出力・保存以外の経路に流さない**
 * （`SetupUiState` と同じ約束。[toString] でも伏せる）。
 *
 * [SetupUiState][com.walkingrpg.app.ui.setup.SetupUiState] を使い回さないのは、
 * ウィザードの進行（`step` / `progress`）が設定画面では意味を持たないから。
 * UseCaseはセットアップと100%共有しているが、状態の形だけは別に持つ。
 *
 * @param savedLlmSummary 保存済みのLLM接続の現在値（キーは含めない）。`null` なら未設定。
 * @param homeRegistered 自宅が登録済みか。**座標は持たない**（画面にも出さない。
 *  CONTRIBUTING.md「位置情報の扱い」）。
 */
data class SettingsUiState(
    val isLoading: Boolean = true,

    // --- LLM接続 ---
    val savedLlmSummary: SavedLlmSummary? = null,
    val llmFormat: LlmFormat = LlmFormat.ANTHROPIC,
    val baseUrl: String = LlmFormat.ANTHROPIC.defaultBaseUrl,
    val model: String = LlmFormat.ANTHROPIC.defaultModel,
    val apiKey: String = "",
    val isTestingLlm: Boolean = false,
    val llmError: String? = null,
    val llmSaved: Boolean = false,

    // --- 天候 ---
    val weatherProvider: WeatherProviderChoice = WeatherProviderChoice.OPEN_METEO,
    val weatherApiKey: String = "",
    val isSavingWeather: Boolean = false,
    val weatherError: String? = null,
    val weatherSaved: Boolean = false,

    // --- 位置情報・自宅 ---
    val permission: LocationPermissionStatus = LocationPermissionStatus.UNKNOWN,
    val homeRegistered: Boolean = false,
    val homeBlurRadiusMeters: Int = DEFAULT_HOME_BLUR_RADIUS_METERS,
    val isRegisteringHome: Boolean = false,
    val homeError: String? = null,
    val homeNotice: String? = null,

    // --- 対象圏 ---
    val osmCounts: OsmMasterCounts? = null,
    val isImporting: Boolean = false,
    val importResult: OsmImportResult? = null,
    val importError: String? = null,

    // --- バックアップ（issue #18） ---
    val isExportingBackup: Boolean = false,
    val backupExportResult: ExportBackupResult? = null,
    val backupExportError: String? = null,
    val isImportingBackup: Boolean = false,
    /** インポートの確認ダイアログを出しているか（破壊的操作なので必ず1枚挟む）。 */
    val backupImportConfirming: Boolean = false,
    val backupImportResult: ImportBackupResult.Success? = null,
    val backupImportError: String? = null,
) {
    val needsPermission: Boolean get() = permission != LocationPermissionStatus.GRANTED

    val weatherNeedsApiKey: Boolean get() = weatherProvider.requiresApiKey

    internal fun currentLlmSettings(): LlmConnectionSettings = LlmConnectionSettings(
        format = llmFormat,
        baseUrl = baseUrl,
        model = model,
        apiKey = apiKey,
    )

    internal fun currentWeatherSettings(): WeatherSettings =
        WeatherSettings(provider = weatherProvider, apiKey = weatherApiKey)

    /**
     * data class の既定実装はAPIキーをそのまま出してしまうので、伏せた形に差し替える
     * （`SetupUiState` と同じ理由）。
     */
    override fun toString(): String = "SettingsUiState(" +
        "isLoading=$isLoading, savedLlmSummary=$savedLlmSummary, " +
        "llmFormat=$llmFormat, baseUrl=$baseUrl, model=$model, apiKey=$MASKED, " +
        "isTestingLlm=$isTestingLlm, llmError=$llmError, llmSaved=$llmSaved, " +
        "weatherProvider=$weatherProvider, weatherApiKey=$MASKED, " +
        "isSavingWeather=$isSavingWeather, weatherError=$weatherError, " +
        "weatherSaved=$weatherSaved, permission=$permission, " +
        "homeRegistered=$homeRegistered, homeBlurRadiusMeters=$homeBlurRadiusMeters, " +
        "isRegisteringHome=$isRegisteringHome, homeError=$homeError, homeNotice=$homeNotice, " +
        "osmCounts=$osmCounts, isImporting=$isImporting, importResult=$importResult, " +
        "importError=$importError, isExportingBackup=$isExportingBackup, " +
        "backupExportResult=$backupExportResult, backupExportError=$backupExportError, " +
        "isImportingBackup=$isImportingBackup, " +
        "backupImportConfirming=$backupImportConfirming, " +
        "backupImportResult=$backupImportResult, backupImportError=$backupImportError)"
}

/**
 * 保存済みのLLM接続を画面に出すための要約。
 *
 * **キーそのものは持たない**（[hasApiKey] だけ）。現在値の表示に鍵の中身は要らないので、
 * 表示専用の型からは落としておく。編集フォーム側（[SettingsUiState.apiKey]）にしか
 * 実物は載らない。
 */
data class SavedLlmSummary(
    val format: LlmFormat,
    val baseUrl: String,
    val model: String,
    val hasApiKey: Boolean,
)

/** 秘密をログに出さないための伏せ字。 */
private const val MASKED = "***"

/**
 * 設定画面のViewModel（issue #20）。
 *
 * 役割規約（architecture.md §2）どおり `StateFlow<UiState>` の組み立てと
 * UseCase呼び出しだけを行う。UseCaseは初回セットアップ（issue #6）と同じものを使う
 * ＝「変更するときは疎通テストを通す」がUseCase側の性質として保たれる
 * （[TestLlmConnectionUseCase] は成功時にしか保存しない）。
 *
 * ## ここに置かないもの
 *
 * - **LLMキーの削除**：`AppViewModel` のセットアップゲートは起動時に一度読んだ結果を
 *   握っているので、実行中にキーを消すと「キー無しでReady」という不整合ができる。
 *   変更（新しい値で疎通 → 保存）だけを口として持つ
 * - **天候の疎通テスト**：現状は形式チェックだけ（`WeatherSettingsValidator`）。
 *   実際に叩いて確かめるのは #11 の Provider 実装の領分でスコープ外
 * - **自宅の座標入力**：CONTRIBUTING.md「位置情報の扱い」。取得元は現在地のみ
 * - **デバッグ情報（直近の失敗理由）**：`AppViewModel` が持つ状態をそのまま画面へ流す
 *   （[SettingsDebugInfo]）。ここに写すと二重管理になる
 */
class SettingsViewModel(
    private val loadSetupSettings: LoadSetupSettingsUseCase,
    private val testLlmConnection: TestLlmConnectionUseCase,
    private val saveWeatherSettings: SaveWeatherSettingsUseCase,
    private val registerHomeAnchor: RegisterHomeAnchorUseCase,
    private val updateHomeBlurRadius: UpdateHomeBlurRadiusUseCase,
    private val reimportOsmArea: ReimportOsmAreaUseCase,
    private val getOsmMasterCounts: GetOsmMasterCountsUseCase,
    private val exportBackup: ExportBackupUseCase,
    private val importBackup: ImportBackupUseCase,
    observeLocationPermission: ObserveLocationPermissionUseCase,
    private val requestLocationPermission: RequestLocationPermissionUseCase,
    private val refreshLocationPermission: RefreshLocationPermissionUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val saved = loadSetupSettings()
            _uiState.update { state ->
                state.copy(
                    isLoading = false,
                    savedLlmSummary = saved.llm?.let { llm ->
                        SavedLlmSummary(
                            format = llm.format,
                            baseUrl = llm.baseUrl,
                            model = llm.model,
                            hasApiKey = llm.apiKey.isNotBlank(),
                        )
                    },
                    // 編集フォームは現在値から始める（丸ごと打ち直させない）
                    llmFormat = saved.llm?.format ?: state.llmFormat,
                    baseUrl = saved.llm?.baseUrl ?: state.baseUrl,
                    model = saved.llm?.model ?: state.model,
                    apiKey = saved.llm?.apiKey ?: state.apiKey,
                    weatherProvider = saved.weather.provider,
                    weatherApiKey = saved.weather.apiKey,
                    homeRegistered = saved.home != null,
                    homeBlurRadiusMeters = saved.home?.blurRadiusMeters
                        ?: state.homeBlurRadiusMeters,
                )
            }
        }
        viewModelScope.launch {
            observeLocationPermission().collect { permission ->
                _uiState.update { it.copy(permission = permission) }
            }
        }
        viewModelScope.launch { refreshOsmCounts() }
    }

    /** 画面に戻ってきたときに権限の付与状況を読み直す。 */
    fun onScreenResumed() {
        refreshLocationPermission()
    }

    // --- LLM接続 ---

    /** フォーマットを変えたらベースURL・モデル名に既定値を入れ直す（`SetupViewModel` と同じ）。 */
    fun onLlmFormatSelected(format: LlmFormat) {
        _uiState.update {
            it.copy(
                llmFormat = format,
                baseUrl = format.defaultBaseUrl,
                model = format.defaultModel,
                llmError = null,
                llmSaved = false,
            )
        }
    }

    fun onBaseUrlChanged(value: String) = updateLlmInput { it.copy(baseUrl = value) }

    fun onModelChanged(value: String) = updateLlmInput { it.copy(model = value) }

    fun onApiKeyChanged(value: String) = updateLlmInput { it.copy(apiKey = value) }

    private fun updateLlmInput(transform: (SettingsUiState) -> SettingsUiState) {
        _uiState.update { transform(it).copy(llmError = null, llmSaved = false) }
    }

    /**
     * 疎通テストして保存する。**保存はUseCase側で疎通成功時にだけ起きる**
     * （[TestLlmConnectionUseCase]）＝この1本が「変更時は疎通テスト」の実装。
     *
     * 待っている間に入力が変わっていたら結果を捨てるのは `SetupViewModel` と同じ
     * （テスト対象でない設定に「保存しました」を出さない）。ただし保存自体は
     * テスト開始時の設定で済んでいるので、現在値の表示はそのぶんを反映する。
     */
    fun onTestAndSaveLlm() {
        if (_uiState.value.isTestingLlm) return
        val tested = _uiState.value.currentLlmSettings()
        _uiState.update { it.copy(isTestingLlm = true, llmError = null, llmSaved = false) }
        viewModelScope.launch {
            val result = testLlmConnection(tested)
            _uiState.update { state ->
                when (result) {
                    LlmConnectionTestResult.Success -> state.copy(
                        isTestingLlm = false,
                        llmError = null,
                        // 保存されたのはテスト開始時の設定。現在値の表示はそれを映す
                        savedLlmSummary = SavedLlmSummary(
                            format = tested.format,
                            baseUrl = tested.baseUrl,
                            model = tested.model,
                            hasApiKey = tested.apiKey.isNotBlank(),
                        ),
                        llmSaved = state.currentLlmSettings() == tested,
                    )

                    is LlmConnectionTestResult.Failure -> state.copy(
                        isTestingLlm = false,
                        llmSaved = false,
                        // 待っている間に入力が変わっていたら、その失敗は今の入力のものではない
                        llmError = if (state.currentLlmSettings() == tested) result.message else null,
                    )
                }
            }
        }
    }

    // --- 天候 ---

    /**
     * プロバイダを選び直す。**保存は明示（[onSaveWeather]）**。
     * ウィザードは選択の都度保存していたが、設定画面はキー欄を書き換えている途中で
     * 「キーが必要です」を出し続けることになるので、保存の合図を分ける。
     */
    fun onWeatherProviderSelected(provider: WeatherProviderChoice) {
        _uiState.update {
            it.copy(weatherProvider = provider, weatherError = null, weatherSaved = false)
        }
    }

    fun onWeatherApiKeyChanged(value: String) {
        _uiState.update {
            it.copy(weatherApiKey = value, weatherError = null, weatherSaved = false)
        }
    }

    /**
     * 天候設定を保存する。検証（キーが要るのに空でないか）はUseCase側の純関数で、
     * エラーはそのまま画面に出す（`WeatherSettingsError.message`）。
     */
    fun onSaveWeather() {
        if (_uiState.value.isSavingWeather) return
        _uiState.update { it.copy(isSavingWeather = true, weatherError = null, weatherSaved = false) }
        viewModelScope.launch {
            val errors = saveWeatherSettings(_uiState.value.currentWeatherSettings())
            _uiState.update {
                it.copy(
                    isSavingWeather = false,
                    weatherError = errors.firstOrNull()?.message,
                    weatherSaved = errors.isEmpty(),
                )
            }
        }
    }

    // --- 位置情報・自宅 ---

    fun onRequestPermission() {
        requestLocationPermission()
    }

    /**
     * ぼかし半径を変える。
     *
     * **自宅が未登録なら書き戻す先が無い**（[UpdateHomeBlurRadiusUseCase] は `null` を返す）。
     * 選択そのものは画面に残し、登録時に使う値として扱う＝黙って無かったことにしない。
     */
    fun onBlurRadiusSelected(meters: Int) {
        _uiState.update { it.copy(homeBlurRadiusMeters = meters, homeNotice = null) }
        viewModelScope.launch {
            val updated = updateHomeBlurRadius(meters)
            if (updated == null) {
                _uiState.update {
                    it.copy(
                        homeRegistered = false,
                        homeNotice = "自宅が未登録なので、この半径は登録したときに反映されます。",
                    )
                }
            }
        }
    }

    /** 現在地を自宅として登録し直す。座標は画面にも出さない（端末内に置くだけ）。 */
    fun onRegisterHome() {
        val state = _uiState.value
        if (state.isRegisteringHome) return
        if (state.needsPermission) {
            requestLocationPermission()
            return
        }
        _uiState.update { it.copy(isRegisteringHome = true, homeError = null, homeNotice = null) }
        viewModelScope.launch {
            val anchor = registerHomeAnchor(_uiState.value.homeBlurRadiusMeters)
            _uiState.update { current ->
                current.copy(
                    isRegisteringHome = false,
                    homeRegistered = anchor != null || current.homeRegistered,
                    homeError = if (anchor == null) {
                        "現在地が取れませんでした。屋外など測位できる場所でもう一度お試しください。"
                    } else {
                        null
                    },
                    homeNotice = if (anchor != null) "自宅を登録しました。" else null,
                )
            }
        }
    }

    // --- 対象圏 ---

    /**
     * 対象圏を取り込み直す。図鑑進捗の作り直しまで含めて1本
     * （[ReimportOsmAreaUseCase]：POIを入れ替えると進捗が食い違うため）。
     */
    fun onReimportOsmArea() {
        if (_uiState.value.isImporting) return
        _uiState.update { it.copy(isImporting = true, importError = null) }
        viewModelScope.launch {
            try {
                val result = reimportOsmArea()
                _uiState.update { it.copy(isImporting = false, importResult = result) }
                refreshOsmCounts()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                _uiState.update {
                    it.copy(
                        isImporting = false,
                        importError = error.message ?: "取り込みに失敗しました。",
                    )
                }
            }
        }
    }

    // --- バックアップ（issue #18） ---

    /**
     * 全データをzipにして共有UIへ渡す（[ExportBackupUseCase]）。
     *
     * **書き出すzipには自宅を含む実座標が入る**（`BackupData` のKDoc）。
     * 出力先はユーザーが選ぶ共有先だけなので、ここで確認は挟まない
     * （壊れるものが無い＝読み取りだけの操作）。
     */
    fun onExportBackup() {
        if (_uiState.value.isExportingBackup) return
        _uiState.update {
            it.copy(isExportingBackup = true, backupExportError = null, backupExportResult = null)
        }
        viewModelScope.launch {
            try {
                val result = exportBackup()
                _uiState.update {
                    it.copy(isExportingBackup = false, backupExportResult = result)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                _uiState.update {
                    it.copy(
                        isExportingBackup = false,
                        backupExportError = error.message ?: "書き出しに失敗しました。",
                    )
                }
            }
        }
    }

    /**
     * インポートの確認を求める。**押した瞬間には何も起きない**：
     * インポートは現在の記録をすべて置き換える破壊的操作なので、
     * ファイル選択の前に必ず1枚挟む（[onImportBackupConfirmed] が実行の口）。
     */
    fun onImportBackupRequested() {
        if (_uiState.value.isImportingBackup) return
        _uiState.update {
            it.copy(
                backupImportConfirming = true,
                backupImportError = null,
                backupImportResult = null,
            )
        }
    }

    /** 確認ダイアログを閉じる（何も起きない）。 */
    fun onImportBackupDismissed() {
        _uiState.update { it.copy(backupImportConfirming = false) }
    }

    /**
     * 確認のうえでインポートする（[ImportBackupUseCase]）。
     *
     * 検証に失敗した場合はDBに何も書かれていない（UseCase側の保証）ので、
     * エラーを出すだけでよい。成功時のメッセージには**自宅の再登録の案内**が入る
     * （`HOME_NOT_RESTORED_NOTICE`：自宅はバックアップの対象外）。
     */
    fun onImportBackupConfirmed() {
        if (_uiState.value.isImportingBackup) return
        _uiState.update {
            it.copy(
                backupImportConfirming = false,
                isImportingBackup = true,
                backupImportError = null,
                backupImportResult = null,
            )
        }
        viewModelScope.launch {
            try {
                when (val result = importBackup()) {
                    is ImportBackupResult.Success -> {
                        _uiState.update {
                            it.copy(isImportingBackup = false, backupImportResult = result)
                        }
                        // 取り込んだデータに合わせて件数表示を読み直す
                        // （マスタは入れ替わらないが、失敗の表示を残さないため通す）
                        refreshOsmCounts()
                    }

                    // 選ばずに閉じただけ。失敗ではないのでエラーは出さない
                    ImportBackupResult.Cancelled ->
                        _uiState.update { it.copy(isImportingBackup = false) }

                    is ImportBackupResult.Failure -> _uiState.update {
                        it.copy(isImportingBackup = false, backupImportError = result.message)
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                _uiState.update {
                    it.copy(
                        isImportingBackup = false,
                        backupImportError = error.message ?: "取り込みに失敗しました。",
                    )
                }
            }
        }
    }

    private suspend fun refreshOsmCounts() {
        try {
            val counts = getOsmMasterCounts()
            _uiState.update { it.copy(osmCounts = counts) }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            _uiState.update {
                it.copy(
                    importError = "マスタ件数の読み出しに失敗しました: ${error.message ?: "原因不明"}",
                )
            }
        }
    }
}

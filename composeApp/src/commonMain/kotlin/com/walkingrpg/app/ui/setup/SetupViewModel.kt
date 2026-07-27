package com.walkingrpg.app.ui.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.walkingrpg.shared.domain.osm.GetOsmMasterCountsUseCase
import com.walkingrpg.shared.domain.osm.ImportOsmAreaUseCase
import com.walkingrpg.shared.domain.osm.OsmImportResult
import com.walkingrpg.shared.domain.setup.CompleteSetupUseCase
import com.walkingrpg.shared.domain.setup.DEFAULT_HOME_BLUR_RADIUS_METERS
import com.walkingrpg.shared.domain.setup.HomeAnchor
import com.walkingrpg.shared.domain.setup.LlmConnectionSettings
import com.walkingrpg.shared.domain.setup.LlmConnectionTestResult
import com.walkingrpg.shared.domain.setup.LlmFormat
import com.walkingrpg.shared.domain.setup.LoadSetupSettingsUseCase
import com.walkingrpg.shared.domain.setup.RegisterHomeAnchorUseCase
import com.walkingrpg.shared.domain.setup.SaveWeatherSettingsUseCase
import com.walkingrpg.shared.domain.setup.SetupGate
import com.walkingrpg.shared.domain.setup.SetupProgress
import com.walkingrpg.shared.domain.setup.SetupStep
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
 * セットアップウィザードの状態。
 *
 * APIキーは画面に出す都合でここに載るが、**ログ出力・保存以外の経路に流さない**
 * （`toString()` を含め、UiStateごとどこかへ書き出すコードを足さないこと）。
 */
data class SetupUiState(
    val isLoading: Boolean = true,
    val step: SetupStep = SetupStep.WELCOME,
    val progress: SetupProgress = SetupProgress(),

    // --- LLM接続 ---
    val llmFormat: LlmFormat = LlmFormat.ANTHROPIC,
    val baseUrl: String = LlmFormat.ANTHROPIC.defaultBaseUrl,
    val model: String = LlmFormat.ANTHROPIC.defaultModel,
    val apiKey: String = "",
    val isTestingLlm: Boolean = false,
    val llmError: String? = null,

    // --- 天候 ---
    val weatherProvider: WeatherProviderChoice = WeatherProviderChoice.OPEN_METEO,
    val weatherApiKey: String = "",
    val weatherError: String? = null,

    // --- 位置情報・自宅 ---
    val permission: LocationPermissionStatus = LocationPermissionStatus.UNKNOWN,
    val homeBlurRadiusMeters: Int = DEFAULT_HOME_BLUR_RADIUS_METERS,
    val isRegisteringHome: Boolean = false,
    val homeRegistered: Boolean = false,
    val homeError: String? = null,

    // --- 対象圏の取り込み ---
    val isImporting: Boolean = false,
    val importResult: OsmImportResult? = null,
    val importError: String? = null,
) {
    /** 「次へ」を押せるか。判定はドメイン層の [SetupGate] に一本化する。 */
    val canAdvance: Boolean get() = SetupGate.canAdvance(step, progress)

    val needsPermission: Boolean get() = permission != LocationPermissionStatus.GRANTED

    val weatherNeedsApiKey: Boolean get() = weatherProvider.requiresApiKey

    private val llmSettings: LlmConnectionSettings
        get() = LlmConnectionSettings(
            format = llmFormat,
            baseUrl = baseUrl,
            model = model,
            apiKey = apiKey,
        )

    internal fun currentLlmSettings(): LlmConnectionSettings = llmSettings

    internal fun currentWeatherSettings(): WeatherSettings =
        WeatherSettings(provider = weatherProvider, apiKey = weatherApiKey)

    /**
     * data class の既定実装はAPIキーをそのまま出してしまうので、伏せた形に差し替える。
     * 上のKDocの約束（キーを他の経路に流さない）を実装でも守るため。
     */
    override fun toString(): String = "SetupUiState(" +
        "isLoading=$isLoading, step=$step, progress=$progress, " +
        "llmFormat=$llmFormat, baseUrl=$baseUrl, model=$model, apiKey=$MASKED, " +
        "isTestingLlm=$isTestingLlm, llmError=$llmError, " +
        "weatherProvider=$weatherProvider, weatherApiKey=$MASKED, weatherError=$weatherError, " +
        "permission=$permission, homeBlurRadiusMeters=$homeBlurRadiusMeters, " +
        "isRegisteringHome=$isRegisteringHome, homeRegistered=$homeRegistered, " +
        "homeError=$homeError, isImporting=$isImporting, importResult=$importResult, " +
        "importError=$importError)"
}

/** 秘密をログに出さないための伏せ字。 */
private const val MASKED = "***"

/**
 * 初回セットアップのViewModel（issue #6）。
 *
 * 役割規約（architecture.md §2）どおり、`StateFlow<UiState>` の組み立てと
 * UseCase呼び出しだけを行う。進行可否の判定はドメイン層（[SetupGate]）にあり、
 * ここでは持たない。
 */
class SetupViewModel(
    private val loadSetupSettings: LoadSetupSettingsUseCase,
    private val testLlmConnection: TestLlmConnectionUseCase,
    private val saveWeatherSettings: SaveWeatherSettingsUseCase,
    private val registerHomeAnchor: RegisterHomeAnchorUseCase,
    private val updateHomeBlurRadius: UpdateHomeBlurRadiusUseCase,
    private val completeSetup: CompleteSetupUseCase,
    private val importOsmArea: ImportOsmAreaUseCase,
    private val getOsmMasterCounts: GetOsmMasterCountsUseCase,
    observeLocationPermission: ObserveLocationPermissionUseCase,
    private val requestLocationPermission: RequestLocationPermissionUseCase,
    private val refreshLocationPermission: RefreshLocationPermissionUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SetupUiState())
    val uiState: StateFlow<SetupUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val saved = loadSetupSettings()
            val progress = restoredProgress(saved.llm != null, saved.home != null)
            _uiState.update { state ->
                state.copy(
                    isLoading = false,
                    step = resumeStep(progress),
                    llmFormat = saved.llm?.format ?: state.llmFormat,
                    baseUrl = saved.llm?.baseUrl ?: state.baseUrl,
                    model = saved.llm?.model ?: state.model,
                    apiKey = saved.llm?.apiKey ?: state.apiKey,
                    weatherProvider = saved.weather.provider,
                    weatherApiKey = saved.weather.apiKey,
                    homeBlurRadiusMeters = saved.home?.blurRadiusMeters
                        ?: state.homeBlurRadiusMeters,
                    homeRegistered = saved.home != null,
                    progress = progress,
                )
            }
        }
        viewModelScope.launch {
            observeLocationPermission().collect { permission ->
                _uiState.update { it.copy(permission = permission) }
            }
        }
    }

    fun onScreenResumed() {
        refreshLocationPermission()
    }

    // --- 再開時の進捗復元 ---

    /**
     * 永続化された事実から進捗を復元する（プロセス再生成でやり直させないため）。
     *
     * - **保存済みのLLM設定＝疎通済み**。[TestLlmConnectionUseCase] は疎通が通ったときしか
     *   保存しないので、「保存されている＝一度は通った設定」が保たれている
     * - **OSMマスタが端末DBに入っている＝取り込み済み**。取り込みはマスタを作り直す
     *   （冪等）ので、件数が入っていれば対象圏は揃っている
     */
    private suspend fun restoredProgress(
        hasSavedLlm: Boolean,
        hasSavedHome: Boolean,
    ): SetupProgress = SetupProgress(
        llmVerified = hasSavedLlm,
        homeRegistered = hasSavedHome,
        areaImported = hasImportedArea(),
    )

    private suspend fun hasImportedArea(): Boolean = try {
        val counts = getOsmMasterCounts()
        counts.wayCount > 0 || counts.poiCount > 0
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Throwable) {
        // 件数が読めないだけなら未取り込み扱いでよい（取り込みは冪等なのでやり直せる）
        false
    }

    /** 復元した進捗から、最初の未完了ステップへ。何も残っていなければ「ようこそ」から。 */
    private fun resumeStep(progress: SetupProgress): SetupStep = when {
        !progress.llmVerified && !progress.areaImported && !progress.homeRegistered ->
            SetupStep.WELCOME

        !progress.llmVerified -> SetupStep.LLM
        !progress.areaImported -> SetupStep.AREA
        else -> SetupStep.DONE
    }

    // --- ステップ移動 ---

    fun onNext() {
        val state = _uiState.value
        if (!state.canAdvance) return
        val next = SetupGate.next(state.step) ?: return
        _uiState.update { it.copy(step = next) }
    }

    fun onBack() {
        val previous = SetupGate.previous(_uiState.value.step) ?: return
        _uiState.update { it.copy(step = previous) }
    }

    // --- LLM接続 ---

    /**
     * フォーマットを変えたら、ベースURLとモデル名に既定値を入れ直す。
     * 別フォーマットの設定が混ざったまま疎通させても混乱するだけなので、
     * 疎通済みフラグも落とす。
     */
    fun onLlmFormatSelected(format: LlmFormat) {
        _uiState.update {
            it.copy(
                llmFormat = format,
                baseUrl = format.defaultBaseUrl,
                model = format.defaultModel,
                llmError = null,
                progress = it.progress.copy(llmVerified = false),
            )
        }
    }

    fun onBaseUrlChanged(value: String) = updateLlmInput { it.copy(baseUrl = value) }

    fun onModelChanged(value: String) = updateLlmInput { it.copy(model = value) }

    fun onApiKeyChanged(value: String) = updateLlmInput { it.copy(apiKey = value) }

    /** 入力を触ったら「疎通済み」は無効になる（古い成功で先に進ませない）。 */
    private fun updateLlmInput(transform: (SetupUiState) -> SetupUiState) {
        _uiState.update {
            transform(it).copy(llmError = null, progress = it.progress.copy(llmVerified = false))
        }
    }

    /**
     * 疎通テスト。**結果はテスト開始時の入力に紐づく**。
     *
     * 待っている間に入力が変わっていたら、その結果は今の設定のものではないので捨てる
     * （未検証の設定に「疎通済み」が立たないようにする）。入力を触った時点で
     * [updateLlmInput] が疎通済みフラグを落としているので、捨てれば未検証のまま残る。
     */
    fun onTestLlmConnection() {
        if (_uiState.value.isTestingLlm) return
        val tested = _uiState.value.currentLlmSettings()
        _uiState.update { it.copy(isTestingLlm = true, llmError = null) }
        viewModelScope.launch {
            val result = testLlmConnection(tested)
            _uiState.update { state ->
                if (state.currentLlmSettings() != tested) {
                    return@update state.copy(isTestingLlm = false)
                }
                when (result) {
                    LlmConnectionTestResult.Success -> state.copy(
                        isTestingLlm = false,
                        llmError = null,
                        progress = state.progress.copy(llmVerified = true),
                    )

                    is LlmConnectionTestResult.Failure -> state.copy(
                        isTestingLlm = false,
                        llmError = result.message,
                        progress = state.progress.copy(llmVerified = false),
                    )
                }
            }
        }
    }

    // --- 天候 ---

    fun onWeatherProviderSelected(provider: WeatherProviderChoice) {
        _uiState.update { it.copy(weatherProvider = provider, weatherError = null) }
        saveWeather()
    }

    fun onWeatherApiKeyChanged(value: String) {
        _uiState.update { it.copy(weatherApiKey = value, weatherError = null) }
        saveWeather()
    }

    private fun saveWeather() {
        viewModelScope.launch {
            val errors = saveWeatherSettings(_uiState.value.currentWeatherSettings())
            _uiState.update { state ->
                state.copy(
                    weatherError = errors.firstOrNull()?.message,
                    progress = state.progress.copy(weatherReady = errors.isEmpty()),
                )
            }
        }
    }

    // --- 位置情報・自宅 ---

    fun onRequestPermission() {
        requestLocationPermission()
    }

    fun onBlurRadiusSelected(meters: Int) {
        _uiState.update { it.copy(homeBlurRadiusMeters = meters) }
        // 未登録なら保存対象がないので、登録済みのときだけ書き戻す
        if (!_uiState.value.homeRegistered) return
        viewModelScope.launch { updateHomeBlurRadius(meters) }
    }

    /** 現在地を自宅として登録する。座標は画面にも出さない（端末内に置くだけ）。 */
    fun onRegisterHome() {
        val state = _uiState.value
        if (state.isRegisteringHome) return
        if (state.needsPermission) {
            requestLocationPermission()
            return
        }
        _uiState.update { it.copy(isRegisteringHome = true, homeError = null) }
        viewModelScope.launch {
            val anchor: HomeAnchor? = registerHomeAnchor(_uiState.value.homeBlurRadiusMeters)
            _uiState.update { current ->
                current.copy(
                    isRegisteringHome = false,
                    homeRegistered = anchor != null,
                    homeError = if (anchor == null) {
                        "現在地が取れませんでした。屋外など測位できる場所でもう一度お試しください。"
                    } else {
                        null
                    },
                    progress = current.progress.copy(homeRegistered = anchor != null),
                )
            }
        }
    }

    // --- 対象圏の取り込み ---

    fun onImportArea() {
        if (_uiState.value.isImporting) return
        _uiState.update { it.copy(isImporting = true, importError = null) }
        viewModelScope.launch {
            try {
                val result = importOsmArea()
                _uiState.update {
                    it.copy(
                        isImporting = false,
                        importResult = result,
                        progress = it.progress.copy(areaImported = true),
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                _uiState.update {
                    it.copy(
                        isImporting = false,
                        importError = error.message ?: "取り込みに失敗しました。",
                        progress = it.progress.copy(areaImported = false),
                    )
                }
            }
        }
    }

    // --- 完了 ---

    /** 完了条件を満たしていれば完了フラグを立て、[onCompleted] を呼ぶ。 */
    fun onFinish(onCompleted: () -> Unit) {
        viewModelScope.launch {
            if (completeSetup(_uiState.value.progress)) onCompleted()
        }
    }
}

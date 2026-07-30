package com.walkingrpg.app.ui.settings

import com.walkingrpg.app.ui.setup.FakeCurrentLocationRepository
import com.walkingrpg.app.ui.setup.FakeOsmAreaSource
import com.walkingrpg.app.ui.setup.FakeOsmMasterRepository
import com.walkingrpg.app.ui.setup.FakePermissionRepository
import com.walkingrpg.app.ui.setup.FakeSetupRepository
import com.walkingrpg.app.ui.setup.ImmediateLlmConnectionTester
import com.walkingrpg.shared.domain.codex.CodexProgress
import com.walkingrpg.shared.domain.codex.CodexProgressRepository
import com.walkingrpg.shared.domain.codex.RecomputeCodexProgressUseCase
import com.walkingrpg.shared.domain.matching.Passage
import com.walkingrpg.shared.domain.matching.PassageRepository
import com.walkingrpg.shared.domain.matching.SessionVisit
import com.walkingrpg.shared.domain.osm.GetOsmMasterCountsUseCase
import com.walkingrpg.shared.domain.osm.ImportOsmAreaUseCase
import com.walkingrpg.shared.domain.osm.OsmMasterCounts
import com.walkingrpg.shared.domain.osm.ReimportOsmAreaUseCase
import com.walkingrpg.shared.domain.setup.HomeAnchor
import com.walkingrpg.shared.domain.setup.LlmConnectionFailure
import com.walkingrpg.shared.domain.setup.LlmConnectionSettings
import com.walkingrpg.shared.domain.setup.LlmConnectionTestResult
import com.walkingrpg.shared.domain.setup.LlmConnectionTester
import com.walkingrpg.shared.domain.setup.LlmFormat
import com.walkingrpg.shared.domain.setup.LoadSetupSettingsUseCase
import com.walkingrpg.shared.domain.setup.RegisterHomeAnchorUseCase
import com.walkingrpg.shared.domain.setup.SaveWeatherSettingsUseCase
import com.walkingrpg.shared.domain.setup.SetupRepository
import com.walkingrpg.shared.domain.setup.TestLlmConnectionUseCase
import com.walkingrpg.shared.domain.setup.UpdateHomeBlurRadiusUseCase
import com.walkingrpg.shared.domain.setup.WeatherProviderChoice
import com.walkingrpg.shared.domain.setup.WeatherSettings
import com.walkingrpg.shared.domain.walk.LocationPermissionStatus
import com.walkingrpg.shared.domain.walk.ObserveLocationPermissionUseCase
import com.walkingrpg.shared.domain.walk.RefreshLocationPermissionUseCase
import com.walkingrpg.shared.domain.walk.RequestLocationPermissionUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 設定画面ViewModelの検証（issue #20）。
 *
 * 見たいのは5つ：
 * - 保存済みの設定が現在値として出ること（キーは伏せた形で）
 * - LLMの変更は**疎通が通ったときだけ保存される**こと（UseCase任せだが、画面の表示も追従する）
 * - 天候は形式エラーをそのまま出すこと
 * - 自宅が未登録のときのぼかし半径変更が「効かない」ことを黙って隠さないこと
 * - 再取り込みで図鑑進捗の作り直しまで走ること（POI入れ替えで進捗が食い違わない）
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val savedLlm = LlmConnectionSettings(
        format = LlmFormat.ANTHROPIC,
        baseUrl = "https://example.invalid",
        model = "test-model",
        apiKey = "dummy-key-for-test",
    )

    // --- 読み込み表示 ---

    @Test
    fun 保存済みの設定を現在値として読み込む() = runTest(dispatcher) {
        val viewModel = viewModel(
            repository = FakeSetupRepository(
                llm = savedLlm,
                weather = WeatherSettings(WeatherProviderChoice.OPEN_WEATHER_MAP, "dummy-weather"),
                home = HomeAnchor(latitude = 12.0, longitude = 34.0, blurRadiusMeters = 300),
            ),
            counts = OsmMasterCounts(wayCount = 12, poiCount = 3),
        )
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        val summary = assertNotNull(state.savedLlmSummary)
        assertEquals(LlmFormat.ANTHROPIC, summary.format)
        assertEquals("https://example.invalid", summary.baseUrl)
        assertEquals("test-model", summary.model)
        assertTrue(summary.hasApiKey, "保存済みのキーが「未設定」に見えている")
        // 編集フォームは現在値から始まる
        assertEquals("dummy-key-for-test", state.apiKey)
        assertEquals(WeatherProviderChoice.OPEN_WEATHER_MAP, state.weatherProvider)
        assertTrue(state.homeRegistered)
        assertEquals(300, state.homeBlurRadiusMeters)
        assertEquals(OsmMasterCounts(wayCount = 12, poiCount = 3), state.osmCounts)
    }

    @Test
    fun 未設定なら現在値は空のまま() = runTest(dispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNull(state.savedLlmSummary)
        assertFalse(state.homeRegistered)
    }

    // --- LLMの変更（疎通成功時のみ保存） ---

    @Test
    fun 疎通が通れば保存されて現在値が更新される() = runTest(dispatcher) {
        val repository = FakeSetupRepository()
        val viewModel = viewModel(
            repository = repository,
            tester = ImmediateLlmConnectionTester(LlmConnectionTestResult.Success),
        )
        advanceUntilIdle()

        viewModel.onBaseUrlChanged("https://example.invalid")
        viewModel.onModelChanged("test-model")
        viewModel.onApiKeyChanged("dummy-key-for-test")
        viewModel.onTestAndSaveLlm()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.llmSaved, "疎通が通ったのに保存の合図が出ていない")
        assertNull(state.llmError)
        assertEquals("test-model", assertNotNull(state.savedLlmSummary).model)
        // 保存はUseCase（TestLlmConnectionUseCase）が疎通成功時にだけ行う
        assertEquals("test-model", assertNotNull(repository.savedLlm).model)
    }

    @Test
    fun 疎通が通らなければ保存せずエラーを出す() = runTest(dispatcher) {
        val repository = FakeSetupRepository()
        val failure = LlmConnectionTestResult.Failure(
            reason = LlmConnectionFailure.UNAUTHORIZED,
            detail = null,
        )
        val viewModel = viewModel(
            repository = repository,
            tester = ImmediateLlmConnectionTester(failure),
        )
        advanceUntilIdle()

        viewModel.onBaseUrlChanged("https://example.invalid")
        viewModel.onModelChanged("test-model")
        viewModel.onApiKeyChanged("dummy-key-for-test")
        viewModel.onTestAndSaveLlm()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.llmSaved)
        assertEquals(failure.message, state.llmError)
        assertNull(state.savedLlmSummary, "通らなかった設定が現在値になっている")
        assertNull(repository.savedLlm, "通らない設定が保存されている")
    }

    @Test
    fun 入力を触ればエラーと保存の合図は消える() = runTest(dispatcher) {
        val viewModel = viewModel(
            tester = ImmediateLlmConnectionTester(LlmConnectionTestResult.Success),
        )
        advanceUntilIdle()

        viewModel.onBaseUrlChanged("https://example.invalid")
        viewModel.onModelChanged("test-model")
        viewModel.onApiKeyChanged("dummy-key-for-test")
        viewModel.onTestAndSaveLlm()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.llmSaved)

        viewModel.onModelChanged("another-model")

        assertFalse(
            viewModel.uiState.value.llmSaved,
            "編集中の値に「保存しました」が残っている",
        )
    }

    // --- 天候 ---

    @Test
    fun 天候の設定を保存する() = runTest(dispatcher) {
        val repository = FakeSetupRepository()
        val viewModel = viewModel(repository = repository)
        advanceUntilIdle()

        viewModel.onWeatherProviderSelected(WeatherProviderChoice.VISUAL_CROSSING)
        viewModel.onWeatherApiKeyChanged("dummy-weather-key")
        viewModel.onSaveWeather()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.weatherSaved)
        assertNull(state.weatherError)
        assertEquals(WeatherProviderChoice.VISUAL_CROSSING, repository.savedWeather.provider)
    }

    @Test
    fun キーが要るプロバイダでキーが空なら保存しない() = runTest(dispatcher) {
        val repository = FakeSetupRepository()
        val viewModel = viewModel(repository = repository)
        advanceUntilIdle()

        viewModel.onWeatherProviderSelected(WeatherProviderChoice.OPEN_WEATHER_MAP)
        viewModel.onSaveWeather()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.weatherSaved)
        assertNotNull(state.weatherError)
        // 既定（Open-Meteo）のまま＝矛盾した設定は書かれていない
        assertEquals(WeatherProviderChoice.OPEN_METEO, repository.savedWeather.provider)
    }

    // --- 自宅 ---

    @Test
    fun 自宅が未登録ならぼかし半径の変更は保存されず注記が出る() = runTest(dispatcher) {
        val repository = FakeSetupRepository(home = null)
        val viewModel = viewModel(repository = repository)
        advanceUntilIdle()

        viewModel.onBlurRadiusSelected(300)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        // 選択そのものは残す（登録時に使う値）
        assertEquals(300, state.homeBlurRadiusMeters)
        assertFalse(state.homeRegistered)
        assertNotNull(state.homeNotice, "効かなかったことが画面に出ていない")
        assertNull(repository.savedHome, "未登録なのに自宅が書き込まれている")
    }

    @Test
    fun 登録済みならぼかし半径だけを書き戻す() = runTest(dispatcher) {
        val repository = FakeSetupRepository(
            home = HomeAnchor(latitude = 12.0, longitude = 34.0, blurRadiusMeters = 100),
        )
        val viewModel = viewModel(repository = repository)
        advanceUntilIdle()

        viewModel.onBlurRadiusSelected(300)
        advanceUntilIdle()

        assertEquals(300, assertNotNull(repository.savedHome).blurRadiusMeters)
        assertNull(viewModel.uiState.value.homeNotice)
    }

    @Test
    fun 権限が無ければ自宅登録の前に権限を要求する() = runTest(dispatcher) {
        val repository = FakeSetupRepository()
        val permissions = FakePermissionRepository(LocationPermissionStatus.DENIED)
        val viewModel = viewModel(repository = repository, permissions = permissions)
        advanceUntilIdle()

        viewModel.onRegisterHome()
        advanceUntilIdle()

        assertEquals(1, permissions.requestCount)
        assertNull(repository.savedHome, "権限が無いまま自宅が登録されている")
    }

    @Test
    fun 現在地が取れれば自宅を登録する() = runTest(dispatcher) {
        val repository = FakeSetupRepository()
        val viewModel = viewModel(repository = repository)
        advanceUntilIdle()

        viewModel.onRegisterHome()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.homeRegistered)
        assertNull(viewModel.uiState.value.homeError)
        assertNotNull(repository.savedHome)
    }

    @Test
    fun 現在地が取れなければエラーを出す() = runTest(dispatcher) {
        val repository = FakeSetupRepository()
        val viewModel = viewModel(
            repository = repository,
            location = FakeCurrentLocationRepository(fix = null),
        )
        advanceUntilIdle()

        viewModel.onRegisterHome()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.homeRegistered)
        assertNotNull(viewModel.uiState.value.homeError)
        assertNull(repository.savedHome)
    }

    // --- 対象圏の再取り込み ---

    @Test
    fun 再取り込みで図鑑進捗も作り直される() = runTest(dispatcher) {
        val codexProgressRepository = CountingCodexProgressRepository()
        val viewModel = viewModel(codexProgressRepository = codexProgressRepository)
        advanceUntilIdle()

        viewModel.onReimportOsmArea()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isImporting)
        assertNotNull(state.importResult)
        assertNull(state.importError)
        assertEquals(
            1,
            codexProgressRepository.replaceCount,
            "POIを入れ替えたのに図鑑進捗が作り直されていない",
        )
    }

    @Test
    fun 取り込みが失敗したらエラーを出して図鑑進捗を触らない() = runTest(dispatcher) {
        val codexProgressRepository = CountingCodexProgressRepository()
        val viewModel = viewModel(
            // 現在地が取れない＝通信もDB書き込みもせずに止まる
            location = FakeCurrentLocationRepository(fix = null),
            codexProgressRepository = codexProgressRepository,
        )
        advanceUntilIdle()

        viewModel.onReimportOsmArea()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isImporting)
        assertNotNull(state.importError)
        assertEquals(0, codexProgressRepository.replaceCount)
    }

    // --- ログに秘密を出さない ---

    @Test
    fun UiStateのtoStringにAPIキーは出ない() {
        val state = SettingsUiState(
            apiKey = "dummy-key-for-test",
            weatherApiKey = "dummy-weather-key",
            baseUrl = "https://example.invalid",
        )

        val text = state.toString()

        assertFalse("dummy-key-for-test" in text, text)
        assertFalse("dummy-weather-key" in text, text)
        assertTrue("apiKey=***" in text, text)
        assertTrue("https://example.invalid" in text, text)
    }

    private fun viewModel(
        repository: SetupRepository = FakeSetupRepository(),
        counts: OsmMasterCounts = OsmMasterCounts(wayCount = 0, poiCount = 0),
        tester: LlmConnectionTester = ImmediateLlmConnectionTester(LlmConnectionTestResult.Success),
        location: FakeCurrentLocationRepository = FakeCurrentLocationRepository(),
        permissions: FakePermissionRepository = FakePermissionRepository(),
        codexProgressRepository: CodexProgressRepository = CountingCodexProgressRepository(),
    ): SettingsViewModel {
        val masterRepository = FakeOsmMasterRepository(counts)
        return SettingsViewModel(
            loadSetupSettings = LoadSetupSettingsUseCase(repository),
            testLlmConnection = TestLlmConnectionUseCase(tester, repository),
            saveWeatherSettings = SaveWeatherSettingsUseCase(repository),
            registerHomeAnchor = RegisterHomeAnchorUseCase(location, repository),
            updateHomeBlurRadius = UpdateHomeBlurRadiusUseCase(repository),
            reimportOsmArea = ReimportOsmAreaUseCase(
                importOsmArea = ImportOsmAreaUseCase(
                    areaSource = FakeOsmAreaSource(),
                    masterRepository = masterRepository,
                    currentLocationRepository = location,
                ),
                recomputeCodexProgress = RecomputeCodexProgressUseCase(
                    passageRepository = EmptyPassageRepository(),
                    osmMasterRepository = masterRepository,
                    codexProgressRepository = codexProgressRepository,
                ),
            ),
            getOsmMasterCounts = GetOsmMasterCountsUseCase(masterRepository),
            observeLocationPermission = ObserveLocationPermissionUseCase(permissions),
            requestLocationPermission = RequestLocationPermissionUseCase(permissions),
            refreshLocationPermission = RefreshLocationPermissionUseCase(permissions),
        )
    }
}

/** 図鑑進捗の作り直しが走った回数だけを見る。 */
private class CountingCodexProgressRepository : CodexProgressRepository {
    var replaceCount = 0
        private set

    override suspend fun replaceAllProgresses(progresses: List<CodexProgress>) {
        replaceCount++
    }

    override suspend fun progresses(): List<CodexProgress> = emptyList()
    override suspend fun progress(speciesId: String): CodexProgress? = null
}

/** 通過が1件も無い状態（進捗は0件になるが、作り直しが走ることは見える）。 */
private class EmptyPassageRepository : PassageRepository {
    override suspend fun replaceSessionPassages(sessionId: Long, passages: List<Passage>) = Unit
    override suspend fun passages(sessionId: Long): List<Passage> = emptyList()
    override suspend fun passCountsByWay(): Map<Long, Int> = emptyMap()
    override suspend fun sessionVisitsByWay(): Map<Long, List<SessionVisit>> = emptyMap()
}

package com.walkingrpg.app.ui.setup

import com.walkingrpg.shared.domain.osm.GetOsmMasterCountsUseCase
import com.walkingrpg.shared.domain.osm.ImportOsmAreaUseCase
import com.walkingrpg.shared.domain.osm.OsmMasterCounts
import com.walkingrpg.shared.domain.setup.CompleteSetupUseCase
import com.walkingrpg.shared.domain.setup.LlmConnectionSettings
import com.walkingrpg.shared.domain.setup.LlmConnectionTestResult
import com.walkingrpg.shared.domain.setup.LlmConnectionTester
import com.walkingrpg.shared.domain.setup.LlmFormat
import com.walkingrpg.shared.domain.setup.LoadSetupSettingsUseCase
import com.walkingrpg.shared.domain.setup.RegisterHomeAnchorUseCase
import com.walkingrpg.shared.domain.setup.SaveWeatherSettingsUseCase
import com.walkingrpg.shared.domain.setup.SetupRepository
import com.walkingrpg.shared.domain.setup.SetupStep
import com.walkingrpg.shared.domain.setup.TestLlmConnectionUseCase
import com.walkingrpg.shared.domain.setup.UpdateHomeBlurRadiusUseCase
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
import kotlin.test.assertTrue

/**
 * ウィザードViewModelの検証。
 *
 * 見たいのは2つ：
 * - 疎通テストの結果が**テスト開始時の入力**に紐づいていること（未検証の設定を通さない）
 * - 永続化された事実（保存済みLLM設定・OSMマスタの件数）から進捗を復元すること
 *   （プロセス再生成でやり直しにならない）
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SetupViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        // viewModelScope は Dispatchers.Main を使うのでテスト用に差し替える
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

    // --- 疎通テストと入力の紐づけ ---

    @Test
    fun 疎通テスト中に入力を変えたら結果を捨てる() = runTest(dispatcher) {
        val tester = SuspendingLlmConnectionTester()
        val viewModel = viewModel(tester = tester)
        advanceUntilIdle()

        viewModel.onNext() // ようこそ → LLM
        viewModel.onApiKeyChanged("dummy-key-for-test")
        viewModel.onModelChanged("test-model")
        viewModel.onTestLlmConnection()
        advanceUntilIdle()

        // テストの応答が返る前に入力を変える
        viewModel.onApiKeyChanged("another-key-for-test")
        tester.complete(LlmConnectionTestResult.Success)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(
            state.progress.llmVerified,
            "テスト対象ではない入力に疎通済みが立っている",
        )
        assertFalse(state.isTestingLlm)
        assertFalse(state.canAdvance, "未検証のままLLMステップを抜けられてしまう")
    }

    @Test
    fun 入力が変わっていなければ疎通済みになる() = runTest(dispatcher) {
        val tester = SuspendingLlmConnectionTester()
        val viewModel = viewModel(tester = tester)
        advanceUntilIdle()

        viewModel.onApiKeyChanged("dummy-key-for-test")
        viewModel.onModelChanged("test-model")
        viewModel.onTestLlmConnection()
        advanceUntilIdle()
        tester.complete(LlmConnectionTestResult.Success)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.progress.llmVerified)
        assertFalse(state.isTestingLlm)
    }

    // --- 進捗の復元 ---

    @Test
    fun 保存済みのLLM設定は疎通済みとして復元する() = runTest(dispatcher) {
        val viewModel = viewModel(
            repository = FakeSetupRepository(llm = savedLlm),
            counts = OsmMasterCounts(wayCount = 0, poiCount = 0),
        )
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.progress.llmVerified, "保存済み設定＝疎通済みのはず")
        assertFalse(state.progress.areaImported)
        // 未完了の取り込みステップから再開できる
        assertEquals(SetupStep.AREA, state.step)
        assertEquals("dummy-key-for-test", state.apiKey)
    }

    @Test
    fun マスタが入っていれば取り込み済みとして復元する() = runTest(dispatcher) {
        val viewModel = viewModel(
            repository = FakeSetupRepository(llm = savedLlm),
            counts = OsmMasterCounts(wayCount = 12, poiCount = 3),
        )
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.progress.areaImported)
        assertEquals(SetupStep.DONE, state.step)
        assertTrue(state.canAdvance, "完了条件を満たしているのに「はじめる」が押せない")
    }

    @Test
    fun キーだけ失われていればLLMステップから再開する() = runTest(dispatcher) {
        // クラウド復元後：マスタは残っているが LLM 設定は読めない
        val viewModel = viewModel(
            repository = FakeSetupRepository(llm = null),
            counts = OsmMasterCounts(wayCount = 12, poiCount = 3),
        )
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.progress.llmVerified)
        assertEquals(SetupStep.LLM, state.step)
    }

    @Test
    fun 何も残っていなければようこそから始める() = runTest(dispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertEquals(SetupStep.WELCOME, state.step)
        assertFalse(state.progress.llmVerified)
        assertFalse(state.progress.areaImported)
    }

    // --- ログに秘密を出さない ---

    @Test
    fun UiStateのtoStringにAPIキーは出ない() {
        val state = SetupUiState(
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
        tester: LlmConnectionTester = SuspendingLlmConnectionTester(),
    ): SetupViewModel {
        val masterRepository = FakeOsmMasterRepository(counts)
        val locationRepository = FakeCurrentLocationRepository()
        val permissionRepository = FakePermissionRepository()
        return SetupViewModel(
            loadSetupSettings = LoadSetupSettingsUseCase(repository),
            testLlmConnection = TestLlmConnectionUseCase(tester, repository),
            saveWeatherSettings = SaveWeatherSettingsUseCase(repository),
            registerHomeAnchor = RegisterHomeAnchorUseCase(locationRepository, repository),
            updateHomeBlurRadius = UpdateHomeBlurRadiusUseCase(repository),
            completeSetup = CompleteSetupUseCase(repository),
            importOsmArea = ImportOsmAreaUseCase(
                areaSource = FakeOsmAreaSource(),
                masterRepository = masterRepository,
                currentLocationRepository = locationRepository,
            ),
            getOsmMasterCounts = GetOsmMasterCountsUseCase(masterRepository),
            observeLocationPermission = ObserveLocationPermissionUseCase(permissionRepository),
            requestLocationPermission = RequestLocationPermissionUseCase(permissionRepository),
            refreshLocationPermission = RefreshLocationPermissionUseCase(permissionRepository),
        )
    }
}


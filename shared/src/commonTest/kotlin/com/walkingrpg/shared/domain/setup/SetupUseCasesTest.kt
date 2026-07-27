package com.walkingrpg.shared.domain.setup

import com.walkingrpg.shared.domain.walk.CurrentLocationRepository
import com.walkingrpg.shared.domain.walk.LocationFix
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** UseCaseの検証。保存とテストの境界（インターフェース）はここでフェイクに差し替える。 */
class SetupUseCasesTest {

    private val settings = LlmConnectionSettings(
        format = LlmFormat.ANTHROPIC,
        baseUrl = "https://example.invalid",
        model = "test-model",
        apiKey = "dummy-key-for-test",
    )

    // --- 疎通テスト ---

    @Test
    fun 疎通が通ったときだけ設定を保存する() = runTest {
        val repository = FakeSetupRepository()
        val useCase = TestLlmConnectionUseCase(
            FakeLlmConnectionTester(LlmConnectionTestResult.Success),
            repository,
        )

        val result = useCase(settings)

        assertEquals(LlmConnectionTestResult.Success, result)
        assertEquals(settings, repository.loadLlmConnection())
    }

    @Test
    fun 疎通が通らなければ保存しない() = runTest {
        val repository = FakeSetupRepository()
        val useCase = TestLlmConnectionUseCase(
            FakeLlmConnectionTester(
                LlmConnectionTestResult.Failure(LlmConnectionFailure.UNAUTHORIZED),
            ),
            repository,
        )

        val result = useCase(settings)

        assertIs<LlmConnectionTestResult.Failure>(result)
        assertNull(repository.loadLlmConnection())
    }

    @Test
    fun 入力が不正なら通信せずに弾く() = runTest {
        val tester = FakeLlmConnectionTester(LlmConnectionTestResult.Success)
        val useCase = TestLlmConnectionUseCase(tester, FakeSetupRepository())

        val result = useCase(settings.copy(apiKey = ""))

        assertIs<LlmConnectionTestResult.Failure>(result)
        assertEquals(LlmConnectionFailure.INVALID_INPUT, result.reason)
        assertEquals(0, tester.callCount, "検証を通らない設定で通信している")
    }

    @Test
    fun 保存されるのは正規化後の設定() = runTest {
        val repository = FakeSetupRepository()
        val useCase = TestLlmConnectionUseCase(
            FakeLlmConnectionTester(LlmConnectionTestResult.Success),
            repository,
        )

        useCase(settings.copy(baseUrl = "https://example.invalid/", apiKey = " key-with-spaces "))

        val saved = repository.loadLlmConnection()
        assertEquals("https://example.invalid", saved?.baseUrl)
        assertEquals("key-with-spaces", saved?.apiKey)
    }

    // --- 自宅登録 ---

    @Test
    fun 現在地が取れれば自宅として保存する() = runTest {
        val repository = FakeSetupRepository()
        // 架空座標
        val useCase = RegisterHomeAnchorUseCase(
            currentLocationRepository = FakeCurrentLocationRepository(
                LocationFix(
                    timestampMs = 0,
                    latitude = 12.0,
                    longitude = 34.0,
                    accuracyMeters = 5.0,
                ),
            ),
            repository = repository,
        )

        val anchor = useCase(blurRadiusMeters = 300)

        assertEquals(HomeAnchor(12.0, 34.0, 300), anchor)
        assertEquals(anchor, repository.loadHomeAnchor())
    }

    @Test
    fun 現在地が取れなければ保存しない() = runTest {
        val repository = FakeSetupRepository()
        val useCase = RegisterHomeAnchorUseCase(
            currentLocationRepository = FakeCurrentLocationRepository(null),
            repository = repository,
        )

        assertNull(useCase(blurRadiusMeters = 200))
        assertNull(repository.loadHomeAnchor())
    }

    // --- 完了 ---

    @Test
    fun 完了条件を満たさなければフラグを立てない() = runTest {
        val repository = FakeSetupRepository()
        val useCase = CompleteSetupUseCase(repository)

        assertFalse(useCase(SetupProgress(llmVerified = true, areaImported = false)))
        assertFalse(repository.isSetupCompleted())
    }

    @Test
    fun 完了条件を満たせばフラグを立てる() = runTest {
        val repository = FakeSetupRepository()
        val useCase = CompleteSetupUseCase(repository)

        assertTrue(useCase(SetupProgress(llmVerified = true, areaImported = true)))
        assertTrue(repository.isSetupCompleted())
    }
}

/** 疎通テスターのフェイク。呼ばれたかどうかも見たいので記録する。 */
private class FakeLlmConnectionTester(
    private val result: LlmConnectionTestResult,
) : LlmConnectionTester {
    var callCount: Int = 0
        private set

    override suspend fun test(settings: LlmConnectionSettings): LlmConnectionTestResult {
        callCount++
        return result
    }
}

private class FakeCurrentLocationRepository(
    private val fix: LocationFix?,
) : CurrentLocationRepository {
    override suspend fun currentFix(): LocationFix? = fix
}

/** インメモリの [SetupRepository]。保存先の振り分けはデータ層のテストで見る。 */
private class FakeSetupRepository : SetupRepository {
    private var llm: LlmConnectionSettings? = null
    private var weather: WeatherSettings = WeatherSettings()
    private var home: HomeAnchor? = null
    private var completed = false

    override suspend fun loadLlmConnection(): LlmConnectionSettings? = llm

    override suspend fun saveLlmConnection(settings: LlmConnectionSettings) {
        llm = settings
    }

    override suspend fun loadWeatherSettings(): WeatherSettings = weather

    override suspend fun saveWeatherSettings(settings: WeatherSettings) {
        weather = settings
    }

    override suspend fun loadHomeAnchor(): HomeAnchor? = home

    override suspend fun saveHomeAnchor(anchor: HomeAnchor) {
        home = anchor
    }

    override suspend fun isSetupCompleted(): Boolean = completed

    override suspend fun markSetupCompleted() {
        completed = true
    }
}

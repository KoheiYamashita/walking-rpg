package com.walkingrpg.app.ui.review

import com.walkingrpg.shared.domain.codex.CodexCategory
import com.walkingrpg.shared.domain.codex.GetCodexUseCase
import com.walkingrpg.shared.domain.codex.Species
import com.walkingrpg.shared.domain.growth.GrowthStage
import com.walkingrpg.shared.domain.llm.GetSpeciesDescriptionUseCase
import com.walkingrpg.shared.domain.llm.LlmCacheEntry
import com.walkingrpg.shared.domain.llm.LlmGenerationConfig
import com.walkingrpg.shared.domain.llm.LlmTaskKind
import com.walkingrpg.shared.domain.llm.ObserveWalkReviewRemarkUseCase
import com.walkingrpg.shared.domain.llm.WalkReviewRemarkFacts
import com.walkingrpg.shared.domain.llm.WalkReviewRemarkPromptBuilder
import com.walkingrpg.shared.domain.matching.Passage
import com.walkingrpg.shared.domain.osm.Way
import com.walkingrpg.shared.domain.review.GetWalkReviewUseCase
import com.walkingrpg.shared.domain.review.TimeOfDay
import com.walkingrpg.shared.domain.review.TimeOfDayResolver
import com.walkingrpg.shared.domain.review.WalkReview
import com.walkingrpg.shared.domain.walk.SessionEndReason
import com.walkingrpg.shared.domain.walk.WalkSession
import com.walkingrpg.shared.domain.weather.ObserveSessionWeatherUseCase
import com.walkingrpg.shared.domain.weather.SessionWeather
import com.walkingrpg.shared.domain.weather.WeatherCondition
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
 * 振り返り画面の状態の組み立て（issue #15）。
 *
 * 見たいのは4つ（architecture.md §5「数値は即時、文章は遅延OK」の検証そのもの）：
 * - 数値サマリは**文章を待たずに**出る（そのとき一言は定型文）
 * - 生成が届いたら一言だけが差し替わる（数値は組み直さない）
 * - プロンプトが変わった古い一言は未生成扱い（`GetPoiFlavorUseCase` と同じ規約）
 * - 天候が後から埋まったら数値サマリに載り、一言の判定もやり直す
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReviewViewModelTest {

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

    private val sessionId = 3L
    private val startedAtMs = 1_767_225_600_000L
    private val durationMs = 30 * 60_000L

    private val species = Species(
        id = "water_test",
        name = "テストトンボ",
        category = CodexCategory.WATER,
        requiredVisitCount = 3,
        foreshadowText = "水面の上を、何かが低く行き来している。",
    )

    private class FixedTimeOfDayResolver : TimeOfDayResolver {
        override fun timeOfDay(epochMillis: Long): TimeOfDay = TimeOfDay.EVENING
    }

    private inner class Fixture(sessionExists: Boolean = true) {
        val cache = FakeLlmCacheRepository()
        val weather = FakeSessionWeatherRepository()

        private val session = WalkSession(
            id = sessionId,
            startedAtMs = startedAtMs,
            endedAtMs = startedAtMs + durationMs,
            endReason = SessionEndReason.AUTO_ARRIVAL,
        ).takeIf { sessionExists }

        /** 2.3kmの散歩1回ぶん（design.md §3 のウォークスルーの数字）。 */
        val getWalkReview = GetWalkReviewUseCase(
            walkSessionRepository = SingleSessionRepository(session),
            passageRepository = StubPassageRepository(
                sessionPassages = mapOf(
                    sessionId to listOf(
                        Passage(sessionId = sessionId, wayId = 1L, timestampMs = startedAtMs),
                    ),
                ),
                passCounts = mapOf(1L to 20),
            ),
            osmMasterRepository = StubOsmMasterRepository(
                ways = listOf(
                    Way(
                        id = 1L,
                        name = null,
                        highway = "residential",
                        geometry = emptyList(),
                        lengthMeters = 2_300.0,
                    ),
                ),
            ),
            sessionWeatherRepository = weather,
            getCodex = GetCodexUseCase(
                codexProgressRepository = EmptyCodexProgressRepository(),
                getSpeciesDescription = GetSpeciesDescriptionUseCase(cache),
                recentCodexRepository = EmptyRecentCodexRepository(),
                catalog = listOf(species),
            ),
            catalog = listOf(species),
        )

        fun viewModel() = ReviewViewModel(
            getWalkReview = getWalkReview,
            observeWalkReviewRemark = ObserveWalkReviewRemarkUseCase(
                cacheRepository = cache,
                timeOfDayResolver = FixedTimeOfDayResolver(),
            ),
            observeSessionWeather = ObserveSessionWeatherUseCase(weather),
        )
    }

    private fun cacheKey() = "WALK_REVIEW_REMARK:session:$sessionId"

    /** 「その振り返りに対して生成済み」の行（指紋の作り方は生成側と同じ）。 */
    private fun generatedEntry(review: WalkReview, text: String) = LlmCacheEntry(
        cacheKey = cacheKey(),
        kind = LlmTaskKind.WALK_REVIEW_REMARK,
        promptHash = WalkReviewRemarkPromptBuilder.promptHash(
            facts = WalkReviewRemarkFacts.of(review, TimeOfDay.EVENING),
            maxTokens = LlmGenerationConfig.DEFAULT.walkReviewRemarkMaxTokens,
        ),
        text = text,
        createdAtMs = 1L,
    )

    @Test
    fun 数値サマリは文章を待たずに出る() = runTest(dispatcher) {
        val fixture = Fixture()
        val viewModel = fixture.viewModel()

        viewModel.onSessionSelected(sessionId)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertEquals(2_300.0, state.review?.distanceMeters)
        assertEquals(durationMs, state.review?.durationMs)
        assertEquals(GrowthStage.TREE, state.review?.grownWays?.single()?.to, "木に上がった")
        val remark = assertNotNull(state.remark)
        assertFalse(remark.isGenerated, "未生成のあいだは定型文")
        assertTrue(remark.text.isNotBlank(), "文章の枠は空にならない")
    }

    @Test
    fun 生成が届いたら一言だけ差し替わる() = runTest(dispatcher) {
        val fixture = Fixture()
        val viewModel = fixture.viewModel()
        viewModel.onSessionSelected(sessionId)
        advanceUntilIdle()
        val numbersBefore = viewModel.uiState.value.review
        val review = assertNotNull(numbersBefore)

        fixture.cache.save(generatedEntry(review, "おかえり。木がひとつ増えたね。"))
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("おかえり。木がひとつ増えたね。", state.remark?.text)
        assertTrue(state.remark?.isGenerated == true)
        assertEquals(numbersBefore, state.review, "数値は組み直さない")
    }

    @Test
    fun プロンプトが変わった古い一言は未生成として扱う() = runTest(dispatcher) {
        val fixture = Fixture()
        fixture.cache.save(
            LlmCacheEntry(
                cacheKey = cacheKey(),
                kind = LlmTaskKind.WALK_REVIEW_REMARK,
                promptHash = "0000000000000000",
                text = "古い方針で書かれた一言。",
                createdAtMs = 1L,
            ),
        )
        val viewModel = fixture.viewModel()

        viewModel.onSessionSelected(sessionId)
        advanceUntilIdle()

        val remark = assertNotNull(viewModel.uiState.value.remark)
        assertFalse(remark.isGenerated)
        assertFalse(
            remark.text == "古い方針で書かれた一言。",
            "定型文に戻す（次のドレインが作り直す）",
        )
    }

    @Test
    fun 天候が後から埋まると数値サマリに載る() = runTest(dispatcher) {
        val fixture = Fixture()
        val viewModel = fixture.viewModel()
        viewModel.onSessionSelected(sessionId)
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.review?.weather, "開いた時点では未取得")

        fixture.weather.save(
            SessionWeather(sessionId, WeatherCondition.CLOUDY, 18.0, fetchedAtMs = 1L),
        )
        advanceUntilIdle()

        assertEquals(WeatherCondition.CLOUDY, viewModel.uiState.value.review?.weather?.condition)
    }

    @Test
    fun 天候込みで生成された一言も差し込まれる() = runTest(dispatcher) {
        // 天候が埋まると一言のプロンプト指紋が変わるので、購読を作り直さないと出ない
        val fixture = Fixture()
        val weather = SessionWeather(sessionId, WeatherCondition.CLEAR, 18.0, fetchedAtMs = 1L)
        val viewModel = fixture.viewModel()
        viewModel.onSessionSelected(sessionId)
        advanceUntilIdle()
        val review = assertNotNull(viewModel.uiState.value.review)
        fixture.cache.save(generatedEntry(review.copy(weather = weather), "空が広い日だったね。"))
        advanceUntilIdle()
        assertFalse(
            viewModel.uiState.value.remark?.isGenerated == true,
            "天候が未取得のうちは指紋が違う＝未生成扱い",
        )

        fixture.weather.save(weather)
        advanceUntilIdle()

        assertEquals("空が広い日だったね。", viewModel.uiState.value.remark?.text)
        assertTrue(viewModel.uiState.value.remark?.isGenerated == true)
    }

    @Test
    fun 記録が無いセッションは見つからない扱い() = runTest(dispatcher) {
        val fixture = Fixture(sessionExists = false)
        val viewModel = fixture.viewModel()

        viewModel.onSessionSelected(sessionId)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.isMissing)
        assertNull(state.remark, "文章の相手が居ない")
    }

    @Test
    fun 同じセッションで呼び直しても組み直さない() = runTest(dispatcher) {
        // 再構成のたびに組み直すと、差し込み済みの一言が定型文に戻って点滅する
        val fixture = Fixture()
        val viewModel = fixture.viewModel()
        viewModel.onSessionSelected(sessionId)
        advanceUntilIdle()
        val review = assertNotNull(viewModel.uiState.value.review)
        fixture.cache.save(generatedEntry(review, "おかえり。"))
        advanceUntilIdle()

        viewModel.onSessionSelected(sessionId)
        advanceUntilIdle()

        assertEquals("おかえり。", viewModel.uiState.value.remark?.text)
    }
}

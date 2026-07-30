package com.walkingrpg.app.ui.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.walkingrpg.shared.domain.llm.ObserveWalkReviewRemarkUseCase
import com.walkingrpg.shared.domain.llm.WalkReviewRemark
import com.walkingrpg.shared.domain.review.GetWalkReviewUseCase
import com.walkingrpg.shared.domain.review.WalkReview
import com.walkingrpg.shared.domain.weather.ObserveSessionWeatherUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 振り返り画面の状態。immutableに保ち、画面はこれ1つだけを見て描画する。
 *
 * **数値と文章を別のフィールドで持つ**のが要（architecture.md §5「数値は即時、文章は遅延OK」）。
 * [review] が入った瞬間に画面は完成し、[remark] はその後で差し替わる。
 *
 * @param review 数値サマリ。読み込み中・セッションが無ければ `null`。
 * @param remark パートナーの一言。未生成のあいだは定型文が入る（`null` になるのは
 *  [review] が無いときだけ）＝**文章の枠が空のまま出ることはない**。
 * @param error 読み込みに失敗した理由（DBが壊れた等）。
 */
data class ReviewUiState(
    val isLoading: Boolean = true,
    val review: WalkReview? = null,
    val remark: WalkReviewRemark? = null,
    val error: String? = null,
) {
    /** セッションが見つからなかった（エクスポートしたDBを入れ替えた後など）。 */
    val isMissing: Boolean get() = !isLoading && review == null && error == null
}

/**
 * 振り返り画面のViewModel（issue #15・design.md §3 の 17:08「帰宅」）。
 *
 * 役割規約（architecture.md §2）：UseCaseを呼んで [StateFlow] を組み立てるだけ。
 * DBにもLLMにも触らない（数値サマリの [GetWalkReviewUseCase] は通信しないので、
 * この画面は圏外でも開く）。
 *
 * ## 引数なしで作れること
 * コンストラクタはUseCaseだけを取り、**どのセッションを見るかは [onSessionSelected] で渡す**。
 * `koinViewModel()` はコンストラクタに画面固有の値を渡せない（渡せる形にすると
 * `AppModuleVerifyTest` のDI検証が「Long の定義が無い」で落ちる）ので、
 * `HomeScreen.onScreenResumed` と同じく `LaunchedEffect` から呼ぶ流儀に合わせている。
 *
 * ## 遅延差し込みは2本
 * 数値サマリを先に出し、そのあと**一言**（`llm_cache` の購読）と**天候**
 * （`session_weather` の購読）が届いたら足す。天候が届いたら一言の購読を作り直すのは、
 * 天候が一言の材料（`WalkReviewRemarkFacts`）に入っているから：作り直さないと、
 * 天候込みで生成された一言をプロンプト不一致（＝未生成）と判定してしまう。
 */
class ReviewViewModel(
    private val getWalkReview: GetWalkReviewUseCase,
    private val observeWalkReviewRemark: ObserveWalkReviewRemarkUseCase,
    private val observeSessionWeather: ObserveSessionWeatherUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ReviewUiState())
    val uiState: StateFlow<ReviewUiState> = _uiState.asStateFlow()

    private var loadedSessionId: Long? = null
    private var remarkJob: Job? = null
    private var weatherJob: Job? = null

    /**
     * 見るセッションを決める（画面から `LaunchedEffect(sessionId)` で1回呼ぶ）。
     *
     * 同じセッションで呼び直されたときは何もしない：再構成のたびに組み直すと、
     * 差し込み済みの一言が定型文に戻って点滅する。
     */
    fun onSessionSelected(sessionId: Long) {
        if (loadedSessionId == sessionId) return
        loadedSessionId = sessionId
        remarkJob?.cancel()
        weatherJob?.cancel()
        _uiState.value = ReviewUiState(isLoading = true)

        viewModelScope.launch {
            val review = try {
                getWalkReview(sessionId)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                _uiState.value = ReviewUiState(
                    isLoading = false,
                    error = error.message ?: "振り返りを読み込めませんでした",
                )
                return@launch
            }

            _uiState.value = ReviewUiState(isLoading = false, review = review)
            if (review == null) return@launch

            observeRemark(review)
            weatherJob = viewModelScope.launch {
                observeSessionWeather(sessionId).collect { weather ->
                    val current = _uiState.value.review ?: return@collect
                    if (current.weather == weather) return@collect
                    val updated = current.copy(weather = weather)
                    _uiState.update { it.copy(review = updated) }
                    // 天候は一言の材料なので、購読ごと作り直す（KDoc「遅延差し込みは2本」）
                    observeRemark(updated)
                }
            }
        }
    }

    private fun observeRemark(review: WalkReview) {
        remarkJob?.cancel()
        remarkJob = viewModelScope.launch {
            observeWalkReviewRemark(review).collect { remark ->
                _uiState.update { it.copy(remark = remark) }
            }
        }
    }
}

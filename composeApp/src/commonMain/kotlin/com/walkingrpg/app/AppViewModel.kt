package com.walkingrpg.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.walkingrpg.shared.domain.growth.RecomputeAfterWalkUseCase
import com.walkingrpg.shared.domain.llm.DrainLlmGenerationQueueUseCase
import com.walkingrpg.shared.domain.review.ConsumePendingReviewUseCase
import com.walkingrpg.shared.domain.review.ObservePendingReviewUseCase
import com.walkingrpg.shared.domain.review.RequestReviewForFinishedWalkUseCase
import com.walkingrpg.shared.domain.setup.IsSetupCompletedUseCase
import com.walkingrpg.shared.domain.snapshot.GenerateMonthlySnapshotsUseCase
import com.walkingrpg.shared.domain.steps.ImportDailyStepsUseCase
import com.walkingrpg.shared.domain.walk.ObserveFinishedWalksUseCase
import com.walkingrpg.shared.domain.walk.ObserveIsWalkingUseCase
import com.walkingrpg.shared.domain.weather.FetchMissingSessionWeatherUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 画面をまたいで効く「アプリ全体の状態」を持つViewModel。
 *
 * 用途は8つ：記録中の画面ON維持、初回セットアップのゲート、散歩が終わったときの
 * 導出の作り直し、天候の後付け取得、歩数の取り込み（押し忘れ救済）、
 * 月次スナップショットの生成（月をまたいだぶんの穴埋め）、LLM生成のドレイン、
 * そして振り返りを開く合図。
 * どれも画面の切り替えとは無関係なアプリ全体の関心事なので、各画面のViewModelに配らず
 * ここ1箇所で持つ（二重管理を避ける。architecture.md §2 の役割規約どおりUseCaseしか知らない）。
 */
class AppViewModel(
    observeIsWalking: ObserveIsWalkingUseCase,
    private val isSetupCompleted: IsSetupCompletedUseCase,
    private val observeFinishedWalks: ObserveFinishedWalksUseCase,
    private val recomputeAfterWalk: RecomputeAfterWalkUseCase,
    private val fetchMissingSessionWeather: FetchMissingSessionWeatherUseCase,
    private val importDailySteps: ImportDailyStepsUseCase,
    private val generateMonthlySnapshots: GenerateMonthlySnapshotsUseCase,
    private val drainLlmGenerationQueue: DrainLlmGenerationQueueUseCase,
    private val requestReviewForFinishedWalk: RequestReviewForFinishedWalkUseCase,
    observePendingReview: ObservePendingReviewUseCase,
    private val consumePendingReview: ConsumePendingReviewUseCase,
) : ViewModel() {

    /** 記録中は画面を消させない（design.md §3「画面はONのまま携行するのが基本」）。 */
    val keepScreenOn: StateFlow<Boolean> = observeIsWalking()
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * これから開くべき振り返りのセッションID（無ければ `null`）。
     *
     * 起点は2つ（`PendingReviewRepository` のKDoc）：散歩の自動終了と
     * 「おかえり」通知のタップ。どちらも同じ1本に集めてあるので、
     * 通知から開いたぶんが二重に開くことはない。消費は [onReviewOpened]。
     */
    val pendingReviewSessionId: StateFlow<Long?> = observePendingReview()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _setupGate = MutableStateFlow(SetupGateState.Loading)

    /**
     * セットアップが済むまでホームに入れない（design.md §9「疎通確認が通るまで
     * プレイを開始できない」・issue #6）。
     */
    val setupGate: StateFlow<SetupGateState> = _setupGate.asStateFlow()

    private val _recomputeError = MutableStateFlow<String?>(null)

    /**
     * 直近の作り直しが失敗した理由（成功したら `null`）。
     *
     * まだ画面には出していない。作り直しは冪等で、次の散歩の終了時に同じことを
     * もう一度やれば揃う（`RecomputeAfterWalkUseCase`）ので、失敗しても失われる記録は無い
     * ＝ここで握りつぶしても嘘にはならない。ただし「何も起きなかったように見える」のは
     * 別の問題なので、原因は状態として残しておく。
     * 出口は設定画面の「デバッグ情報」（issue #20・`SettingsDebugInfo`）。
     * 保持はここのまま＝画面のViewModelに写して二重管理しない。
     */
    val recomputeError: StateFlow<String?> = _recomputeError.asStateFlow()

    private val _weatherError = MutableStateFlow<String?>(null)

    /**
     * 直近の天候取得が失敗した理由（成功したら `null`）。
     *
     * [recomputeError] と同じ扱い：設定画面の「デバッグ情報」に出すだけで、
     * 失敗そのものは握る。天候は取れなくても散歩の記録（真実の源）に欠けは出ない
     * （architecture.md §8「天候APIの欠測」）。
     */
    val weatherError: StateFlow<String?> = _weatherError.asStateFlow()

    private val _llmGenerationError = MutableStateFlow<String?>(null)

    /**
     * 直近のLLM生成が失敗した理由（成功したら `null`）。
     *
     * [recomputeError] / [weatherError] と同じ扱い：設定画面の「デバッグ情報」に出すだけ。
     * 生成できていない地点は定型文で成立する（design.md §7）ので、
     * 失敗しても散歩は始められる。
     */
    val llmGenerationError: StateFlow<String?> = _llmGenerationError.asStateFlow()

    init {
        viewModelScope.launch {
            _setupGate.value =
                if (isSetupCompleted()) SetupGateState.Ready else SetupGateState.Required
        }
        // 起動時のリトライ（design.md §9「失敗時は次回起動時リトライ」）。
        // 圏外で終わった散歩・アプリを閉じたまま日が変わった散歩の天候は、ここで埋まる。
        // 続けてLLMの事前生成も回す：家に居る＝Wi-Fiの見込みが高く、
        // 次の散歩までに地点フレーバーを揃えておける（design.md §7「路上で待たせたら負け」）。
        viewModelScope.launch {
            fetchSessionWeather()
            // 歩数の取り込み（design.md §3「開始を押し忘れた日」）。文章の生成より前に置くのは、
            // 「昨日はけっこう歩いたんだね」が一言の材料になるから（GetWalkRemarkContextUseCase）。
            // 起動のたびに走らせても1日1行に収束する（ImportDailyStepsUseCase は冪等）。
            importSteps()
            // 月をまたいでいれば、まだ作っていない月のスナップショットを作る（issue #17）。
            // 起動時にしか走らない口なので、月末に起きていなくても次の起動で追いつく
            // （GenerateMonthlySnapshotsUseCase のKDoc）。文章の生成より前に置くのは、
            // 通信の要らない処理（数値＋描画）を先に済ませるという既存の順序に合わせて。
            generateSnapshots()
            drainLlmGeneration()
        }
        // 散歩が終わったら passage → way_growth を作り直す（architecture.md §5「帰宅後」）。
        // 画面ではなくここに置くのは、地図を開いていなくても・ホームに居なくても
        // 走らなければならないから。畳み方（手動・自宅到着・測位エラー）の区別は
        // ObserveFinishedWalksUseCase が吸収済み。
        viewModelScope.launch {
            observeFinishedWalks().collect { sessionId ->
                recompute(sessionId)
                // 振り返りを開く合図は集計のあと（数値が確定していない振り返りを見せない）。
                // 放置セッションでは開かない判定は UseCase 側
                // （RequestReviewForFinishedWalkUseCase のKDoc）。
                requestReview(sessionId)
                // 天候は集計のあと。数値（ローカル計算）を通信より先に確定させる
                // （architecture.md §5「数値は即時、文章は遅延OK」と同じ順序）。
                // 起動時の取得と同じ1本を呼ぶだけ＝終わったばかりの散歩が
                // 「未取得の1件」として拾われる。
                fetchSessionWeather()
                // 文章の生成は最後。数値（ローカル計算）→天候→文章の順で、
                // 遅れてよいものを後ろに置く（architecture.md §5）。
                drainLlmGeneration()
            }
        }
    }

    /**
     * ウィザードが完了フラグを立てたあとに呼ばれる。
     * 完了判定は `CompleteSetupUseCase` 側で済んでいるので読み直さない。
     */
    fun onSetupCompleted() {
        _setupGate.value = SetupGateState.Ready
    }

    /** 振り返りを開いたので合図を消す（画面から戻ってきたときに開き直さないため）。 */
    fun onReviewOpened() {
        consumePendingReview()
    }

    /**
     * 畳まれた散歩の振り返りを開くよう頼む。
     *
     * 例外を外に投げないのは [recompute] と同じ理由（`collect` の中で投げると購読ごと終わり、
     * 以降の散歩が集計されなくなる）。振り返りはホームのセッション一覧からも開けるので、
     * ここが失敗しても失われるのは自動遷移だけ。
     */
    private suspend fun requestReview(sessionId: Long) {
        try {
            requestReviewForFinishedWalk(sessionId)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            // 自動で開けなかっただけなので、状態としても残さない（一覧から開ける）。
        }
    }

    /**
     * 1セッションぶんの作り直し。
     *
     * 例外を外に投げないのは、`collect` の中で投げると購読ごと終わり、
     * **以降の散歩がすべて作り直されなくなる**から（1回の失敗が永続的な停止になる）。
     */
    private suspend fun recompute(sessionId: Long) {
        try {
            recomputeAfterWalk(sessionId)
            _recomputeError.value = null
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            _recomputeError.value = error.message ?: "散歩の集計に失敗しました"
        }
    }

    /**
     * 天候がまだ付いていない散歩をまとめて埋める。
     *
     * 例外を外に投げないのは [recompute] と同じ理由（`collect` の中で投げると
     * 購読ごと終わる）。1セッションぶんの通信失敗は
     * `FetchMissingSessionWeatherUseCase` が中で受けて行を作らずに返すので、
     * ここで捕まるのは設定・DBの読み書きが壊れたときだけ。
     * どちらにせよ次の起動でやり直せる（この取り込みは冪等）。
     *
     * 取れなかったセッションが残っていること自体はエラーにしない：
     * 圏外で終えた散歩は「次回リトライ」が正常な流れであって、失敗ではない。
     */
    private suspend fun fetchSessionWeather() {
        try {
            fetchMissingSessionWeather()
            _weatherError.value = null
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            _weatherError.value = error.message ?: "天候の取得に失敗しました"
        }
    }

    /**
     * 歩数計から昨日・今日を取り込む（design.md §3「開始を押し忘れた日」・issue #7）。
     *
     * **失敗は握る**：歩数は「歩いたのに損した」を作らないための救済で、
     * 取れなくても散歩の記録（真実の源）に欠けは出ない
     * （architecture.md §8「権限が取れない場合は機能ごと落とせる設計」）。
     * 権限が無ければ歩数計側が `null` を返して何も起きない
     * （権限要求のUIは別issue。設定画面（#20）には載せなかった：
     * `HealthConnectDailyStepsSource` のKDoc「権限のリクエスト導線」）。
     *
     * 状態としても残さないのは、これが「取れたら嬉しい」情報だから：
     * 取れなかったこと自体は正常な流れで、次の起動でやり直せる（取り込みは冪等）。
     */
    private suspend fun importSteps() {
        try {
            importDailySteps()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            // 歩数が取れなかっただけ。次の起動でやり直す。
        }
    }

    /**
     * まだ作っていない月のスナップショットを作る（design.md §4.5・issue #17）。
     *
     * **失敗は握る**：作れなかった月は次の起動でもう一度対象になる
     * （生成は「行が無い月」を数え直す冪等な処理）。1枚が作れなかったからといって
     * 散歩の記録（真実の源）に欠けは出ないし、既に作った月の画像も無傷
     * （一度作った月は書き換えない＝`Snapshot.sq`）。
     *
     * 状態としても残さないのは、これが「気付いたときに増える」ものだから：
     * 描画に失敗する状況（メモリ不足など）は次の起動では起きていないことが多い。
     */
    private suspend fun generateSnapshots() {
        try {
            generateMonthlySnapshots()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            // アルバムが1枚増えなかっただけ。次の起動でやり直す。
        }
    }

    /**
     * 未生成のテキストをまとめて作る（architecture.md §5「LLM生成キューへ投入」）。
     *
     * 例外を外に投げないのは [recompute] / [fetchSessionWeather] と同じ理由
     * （`collect` の中で投げると購読ごと終わる）。1件ぶんの生成失敗は
     * `DrainLlmGenerationQueueUseCase` が中で受けて保存せずに返すので、
     * ここで捕まるのは設定・DBの読み書きが壊れたときだけ。
     *
     * 生成できなかった地点が残っていること自体はエラーにしない：従量回線・未設定・圏外で
     * 見送るのは正常な流れで、その地点は定型文で成立する（design.md §7）。
     */
    private suspend fun drainLlmGeneration() {
        try {
            drainLlmGenerationQueue()
            _llmGenerationError.value = null
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            _llmGenerationError.value = error.message ?: "文章の生成に失敗しました"
        }
    }
}

/** 起動直後の分岐。判定が終わるまでは [SetupGateState.Loading] で何も出さない。 */
enum class SetupGateState {
    Loading,
    Required,
    Ready,
}

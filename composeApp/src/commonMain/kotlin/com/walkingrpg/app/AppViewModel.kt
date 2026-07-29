package com.walkingrpg.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.walkingrpg.shared.domain.growth.RecomputeAfterWalkUseCase
import com.walkingrpg.shared.domain.setup.IsSetupCompletedUseCase
import com.walkingrpg.shared.domain.walk.ObserveFinishedWalksUseCase
import com.walkingrpg.shared.domain.walk.ObserveIsWalkingUseCase
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
 * 用途は3つ：記録中の画面ON維持、初回セットアップのゲート、そして
 * 散歩が終わったときの導出の作り直し。どれも画面の切り替えとは無関係な
 * アプリ全体の関心事なので、各画面のViewModelに配らずここ1箇所で持つ
 * （二重管理を避ける。architecture.md §2 の役割規約どおりUseCaseしか知らない）。
 */
class AppViewModel(
    observeIsWalking: ObserveIsWalkingUseCase,
    private val isSetupCompleted: IsSetupCompletedUseCase,
    private val observeFinishedWalks: ObserveFinishedWalksUseCase,
    private val recomputeAfterWalk: RecomputeAfterWalkUseCase,
) : ViewModel() {

    /** 記録中は画面を消させない（design.md §3「画面はONのまま携行するのが基本」）。 */
    val keepScreenOn: StateFlow<Boolean> = observeIsWalking()
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

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
     * TODO(#20 設定画面): デバッグ表示の置き場ができたらそこに出す。
     */
    val recomputeError: StateFlow<String?> = _recomputeError.asStateFlow()

    init {
        viewModelScope.launch {
            _setupGate.value =
                if (isSetupCompleted()) SetupGateState.Ready else SetupGateState.Required
        }
        // 散歩が終わったら passage → way_growth を作り直す（architecture.md §5「帰宅後」）。
        // 画面ではなくここに置くのは、地図を開いていなくても・ホームに居なくても
        // 走らなければならないから。畳み方（手動・自宅到着・測位エラー）の区別は
        // ObserveFinishedWalksUseCase が吸収済み。
        viewModelScope.launch {
            observeFinishedWalks().collect { sessionId ->
                recompute(sessionId)
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
}

/** 起動直後の分岐。判定が終わるまでは [SetupGateState.Loading] で何も出さない。 */
enum class SetupGateState {
    Loading,
    Required,
    Ready,
}

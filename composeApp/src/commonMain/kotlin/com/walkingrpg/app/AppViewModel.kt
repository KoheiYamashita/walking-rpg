package com.walkingrpg.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.walkingrpg.shared.domain.setup.IsSetupCompletedUseCase
import com.walkingrpg.shared.domain.walk.ObserveIsWalkingUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 画面をまたいで効く「アプリ全体の状態」を持つViewModel。
 *
 * 用途は2つ：記録中の画面ON維持と、初回セットアップのゲート。どちらも画面の
 * 切り替えとは無関係なアプリ全体の状態なので、各画面のViewModelに配らず
 * ここ1箇所で持つ（二重管理を避ける。architecture.md §2 の役割規約どおり
 * UseCaseしか知らない）。
 */
class AppViewModel(
    observeIsWalking: ObserveIsWalkingUseCase,
    private val isSetupCompleted: IsSetupCompletedUseCase,
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

    init {
        viewModelScope.launch {
            _setupGate.value =
                if (isSetupCompleted()) SetupGateState.Ready else SetupGateState.Required
        }
    }

    /**
     * ウィザードが完了フラグを立てたあとに呼ばれる。
     * 完了判定は `CompleteSetupUseCase` 側で済んでいるので読み直さない。
     */
    fun onSetupCompleted() {
        _setupGate.value = SetupGateState.Ready
    }
}

/** 起動直後の分岐。判定が終わるまでは [SetupGateState.Loading] で何も出さない。 */
enum class SetupGateState {
    Loading,
    Required,
    Ready,
}

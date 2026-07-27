package com.walkingrpg.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.walkingrpg.shared.domain.walk.ObserveIsWalkingUseCase
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * 画面をまたいで効く「アプリ全体の状態」を持つViewModel。
 *
 * いまのところ用途は画面ON維持だけ。記録中かどうかは画面の切り替えとは無関係な
 * ドメイン状態なので、各画面のViewModelに配らず、ここ1箇所で購読する
 * （二重管理を避ける。architecture.md §2 の役割規約どおりUseCaseしか知らない）。
 */
class AppViewModel(
    observeIsWalking: ObserveIsWalkingUseCase,
) : ViewModel() {

    /** 記録中は画面を消させない（design.md §3「画面はONのまま携行するのが基本」）。 */
    val keepScreenOn: StateFlow<Boolean> = observeIsWalking()
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
}

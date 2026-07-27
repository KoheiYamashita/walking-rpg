package com.walkingrpg.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.walkingrpg.shared.platform.Platform
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ホーム画面の状態。
 * UiStateはimmutableに保ち、画面はこれ1つだけを見て描画する（単方向データフロー）。
 */
data class HomeUiState(
    val isLoading: Boolean = true,
    val platformName: String = "",
    val isWalking: Boolean = false,
)

/**
 * ホーム画面のViewModel。
 *
 * 役割規約（architecture.md §2）：
 * `StateFlow<UiState>` の組み立てとUseCase呼び出しのみを行い、
 * プラットフォームAPI・DB・HTTPには直接触らない。
 * 雛形では注入された [Platform] を表示するだけで、UseCaseは後続issueで足す。
 */
class HomeViewModel(
    private val platform: Platform,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = false, platformName = platform.name) }
        }
    }

    fun onToggleWalk() {
        // TODO(後続issue): 散歩セッションの開始・終了UseCaseを呼ぶ
        _uiState.update { it.copy(isWalking = !it.isWalking) }
    }
}

package com.walkingrpg.app.ui.codex

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.walkingrpg.shared.domain.codex.Codex
import com.walkingrpg.shared.domain.codex.GetCodexUseCase
import com.walkingrpg.shared.domain.codex.ObserveCodexUpdatesUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 図鑑画面の状態。immutableに保ち、画面はこれ1つだけを見て描画する。
 *
 * **訪問回数も残り回数も入っていない**（[Codex] のKDoc「数字を出さない」）。
 * design.md §4.4「残り回数は数字で見せず、予兆で匂わせる」がドメイン側で守られているので、
 * UI層はそもそも数字を持てない。
 */
data class CodexUiState(
    val isLoading: Boolean = true,
    val codex: Codex = Codex(),
)

/**
 * 図鑑画面のViewModel。
 *
 * 役割規約（architecture.md §2）：UseCaseを呼んで [StateFlow] を組み立てるだけ。
 * DBにもLLMにも触らない（[GetCodexUseCase] は通信しないので、この画面は圏外でも開く）。
 *
 * 初回読み込みと再読み込みを [ObserveCodexUpdatesUseCase] 1本にまとめてあるのは
 * `MapViewModel` と同じ形：購読開始で現在値が1回流れ、そのあとは散歩終了後の
 * 再計算のたびに流れる＝図鑑を開いたままでも新しい発見と予兆が乗る。
 */
class CodexViewModel(
    private val getCodex: GetCodexUseCase,
    observeCodexUpdates: ObserveCodexUpdatesUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CodexUiState())
    val uiState: StateFlow<CodexUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            observeCodexUpdates().collect { loadCodex() }
        }
    }

    private suspend fun loadCodex() {
        val codex = getCodex()
        _uiState.update { it.copy(isLoading = false, codex = codex) }
    }
}

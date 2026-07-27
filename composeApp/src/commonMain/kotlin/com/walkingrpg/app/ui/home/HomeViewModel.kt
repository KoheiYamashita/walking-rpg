package com.walkingrpg.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.walkingrpg.shared.domain.GetPlatformNameUseCase
import com.walkingrpg.shared.domain.walk.ExportWalkSessionUseCase
import com.walkingrpg.shared.domain.walk.LocationPermissionStatus
import com.walkingrpg.shared.domain.walk.ObserveLocationPermissionUseCase
import com.walkingrpg.shared.domain.walk.ObserveWalkRecordingUseCase
import com.walkingrpg.shared.domain.walk.ObserveWalkSessionsUseCase
import com.walkingrpg.shared.domain.walk.RefreshLocationPermissionUseCase
import com.walkingrpg.shared.domain.walk.RequestLocationPermissionUseCase
import com.walkingrpg.shared.domain.walk.StartWalkSessionUseCase
import com.walkingrpg.shared.domain.walk.StopWalkSessionUseCase
import com.walkingrpg.shared.domain.walk.WalkRecordingSnapshot
import com.walkingrpg.shared.domain.walk.WalkSessionSummary
import kotlinx.coroutines.CancellationException
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
    val recording: WalkRecordingSnapshot = WalkRecordingSnapshot(),
    val permission: LocationPermissionStatus = LocationPermissionStatus.UNKNOWN,
    val sessions: List<WalkSessionSummary> = emptyList(),
    val message: String? = null,
) {
    val isWalking: Boolean get() = recording.isRecording

    /** 記録中は画面を消させない（design.md §3「画面はONのまま携行するのが基本」）。 */
    val keepScreenOn: Boolean get() = isWalking

    val needsPermission: Boolean get() = permission != LocationPermissionStatus.GRANTED
}

/**
 * ホーム画面のViewModel。
 *
 * 役割規約（architecture.md §2）：
 * `StateFlow<UiState>` の組み立てとUseCase呼び出しのみを行い、
 * プラットフォームAPI・DB・HTTPには直接触らない。
 * 依存はUseCase（shared/domain）だけで、その先の
 * Repository実装・expect/actual はここからは見えない。
 */
class HomeViewModel(
    private val getPlatformName: GetPlatformNameUseCase,
    private val observeWalkRecording: ObserveWalkRecordingUseCase,
    private val observeWalkSessions: ObserveWalkSessionsUseCase,
    private val observeLocationPermission: ObserveLocationPermissionUseCase,
    private val startWalkSession: StartWalkSessionUseCase,
    private val stopWalkSession: StopWalkSessionUseCase,
    private val requestLocationPermission: RequestLocationPermissionUseCase,
    private val refreshLocationPermission: RefreshLocationPermissionUseCase,
    private val exportWalkSession: ExportWalkSessionUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = false, platformName = getPlatformName()) }
        }
        viewModelScope.launch {
            observeWalkRecording().collect { snapshot ->
                _uiState.update { it.copy(recording = snapshot) }
            }
        }
        viewModelScope.launch {
            observeWalkSessions().collect { sessions ->
                _uiState.update { it.copy(sessions = sessions) }
            }
        }
        viewModelScope.launch {
            observeLocationPermission().collect { permission ->
                _uiState.update { it.copy(permission = permission) }
            }
        }
    }

    /** 画面に戻ってきたときに権限の付与状況を読み直す。 */
    fun onScreenResumed() {
        refreshLocationPermission()
    }

    fun onRequestPermission() {
        requestLocationPermission()
    }

    fun onToggleWalk() {
        val state = _uiState.value
        if (!state.isWalking && state.needsPermission) {
            requestLocationPermission()
            return
        }
        viewModelScope.launch {
            if (state.isWalking) stopWalkSession() else startWalkSession()
        }
    }

    fun onExportSession(sessionId: Long) {
        viewModelScope.launch {
            try {
                val fileName = exportWalkSession(sessionId)
                _uiState.update { it.copy(message = "$fileName を書き出しました") }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                _uiState.update {
                    it.copy(message = "書き出しに失敗しました: ${error.message ?: "原因不明"}")
                }
            }
        }
    }

    fun onMessageShown() {
        _uiState.update { it.copy(message = null) }
    }
}

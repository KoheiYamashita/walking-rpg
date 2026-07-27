package com.walkingrpg.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.walkingrpg.shared.domain.GetPlatformNameUseCase
import com.walkingrpg.shared.domain.osm.GetOsmMasterCountsUseCase
import com.walkingrpg.shared.domain.osm.ImportOsmAreaUseCase
import com.walkingrpg.shared.domain.osm.OsmImportResult
import com.walkingrpg.shared.domain.osm.OsmMasterCounts
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
    val osmImport: OsmImportUiState = OsmImportUiState(),
    val message: String? = null,
) {
    val isWalking: Boolean get() = recording.isRecording

    val needsPermission: Boolean get() = permission != LocationPermissionStatus.GRANTED
}

/**
 * OSMマスタ取り込み（issue #5）のデバッグUI状態。
 *
 * 本来の導線は初回セットアップ（issue #6）が作る。ここは
 * 「監査値（design.md §9）と件数が乖離していないか」を実機で確かめるための仮表示。
 *
 * @param storedCounts 端末DBに現在入っている件数（再取り込みで増えない＝冪等の目視確認用）。
 * @param lastResult 直近の取り込み1回の内訳（除外件数を含む）。
 */
data class OsmImportUiState(
    val isImporting: Boolean = false,
    val storedCounts: OsmMasterCounts? = null,
    val lastResult: OsmImportResult? = null,
    val error: String? = null,
)

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
    private val importOsmArea: ImportOsmAreaUseCase,
    private val getOsmMasterCounts: GetOsmMasterCountsUseCase,
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
        viewModelScope.launch { refreshOsmCounts() }
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

    /** デバッグ用の取り込みトリガー（issue #5）。本来の導線は初回セットアップ（issue #6）。 */
    fun onImportOsmArea() {
        if (_uiState.value.osmImport.isImporting) return
        _uiState.update { it.copy(osmImport = it.osmImport.copy(isImporting = true, error = null)) }
        viewModelScope.launch {
            try {
                val result = importOsmArea()
                _uiState.update {
                    it.copy(osmImport = it.osmImport.copy(isImporting = false, lastResult = result))
                }
                refreshOsmCounts()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                _uiState.update {
                    it.copy(
                        osmImport = it.osmImport.copy(
                            isImporting = false,
                            error = error.message ?: "原因不明",
                        ),
                    )
                }
            }
        }
    }

    fun onMessageShown() {
        _uiState.update { it.copy(message = null) }
    }

    private suspend fun refreshOsmCounts() {
        try {
            val counts = getOsmMasterCounts()
            _uiState.update { it.copy(osmImport = it.osmImport.copy(storedCounts = counts)) }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            _uiState.update {
                it.copy(
                    osmImport = it.osmImport.copy(
                        error = "マスタ件数の読み出しに失敗しました: ${error.message ?: "原因不明"}",
                    ),
                )
            }
        }
    }
}

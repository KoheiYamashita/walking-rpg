package com.walkingrpg.app.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.walkingrpg.shared.domain.growth.ObserveGrowthUpdatesUseCase
import com.walkingrpg.shared.domain.map.GeoPoint
import com.walkingrpg.shared.domain.map.GetMapSceneUseCase
import com.walkingrpg.shared.domain.map.MapCamera
import com.walkingrpg.shared.domain.map.WayHighlight
import com.walkingrpg.shared.domain.walk.ObserveIsWalkingUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 地図画面の状態。immutableに保ち、画面はこれ1つだけを見て描画する。 */
data class MapUiState(
    val isLoading: Boolean = true,
    val camera: MapCamera = MapCamera(GeoPoint(0.0, 0.0), zoom = 1.0),
    val highlights: List<WayHighlight> = emptyList(),
    /** 現在地。取れなかった（権限なし等）ときは `null` で、マーカーを出さない。 */
    val userLocation: GeoPoint? = null,
    /** 記録中は現在地にカメラを追従させる（design.md §3「歩行中」）。 */
    val isFollowingUser: Boolean = false,
)

/**
 * 地図画面のViewModel。
 *
 * 役割規約（architecture.md §2）：UseCaseを呼んで [StateFlow] を組み立てるだけ。
 * MapLibreにもネットワークにも触らない（触るのはUI層のactual）。
 */
class MapViewModel(
    private val getMapScene: GetMapSceneUseCase,
    observeIsWalking: ObserveIsWalkingUseCase,
    observeGrowthUpdates: ObserveGrowthUpdatesUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MapUiState())
    val uiState: StateFlow<MapUiState> = _uiState.asStateFlow()

    init {
        // 初回読み込みと再読み込みを1本の購読にまとめている。
        // ObserveGrowthUpdatesUseCase の元は StateFlow なので購読開始で現在値が1回流れ、
        // そのあとは散歩終了後の再計算のたびに流れる＝地図を開いたままでも
        // 「今日歩いた道の色が変わっている」が出る（issue #10 の完了条件）。
        viewModelScope.launch {
            observeGrowthUpdates().collect { loadScene() }
        }
        viewModelScope.launch {
            observeIsWalking().collect { isWalking ->
                _uiState.update { it.copy(isFollowingUser = isWalking) }
            }
        }
    }

    private suspend fun loadScene() {
        val scene = getMapScene()
        _uiState.update {
            it.copy(
                isLoading = false,
                camera = scene.camera,
                highlights = scene.highlights,
                userLocation = scene.userLocation,
            )
        }
    }
}

package com.walkingrpg.app.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.walkingrpg.shared.domain.map.GeoPoint
import com.walkingrpg.shared.domain.map.GetMapSceneUseCase
import com.walkingrpg.shared.domain.map.MapCamera
import com.walkingrpg.shared.domain.map.MapTiles
import com.walkingrpg.shared.domain.map.WayHighlight
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 地図画面の状態。immutableに保ち、画面はこれ1つだけを見て描画する。 */
data class MapUiState(
    val isLoading: Boolean = true,
    val camera: MapCamera = MapCamera(GeoPoint(0.0, 0.0), zoom = 1.0),
    /** ローカルPMTilesの絶対パス。null ならタイル未同梱で地図は描けない。 */
    val tilesPath: String? = null,
    val highlights: List<WayHighlight> = emptyList(),
)

/**
 * 地図画面のViewModel。
 *
 * 役割規約（architecture.md §2）：UseCaseを呼んで [StateFlow] を組み立てるだけ。
 * MapLibreにもファイルシステムにも触らない（触るのはUI層のactualとplatform層）。
 */
class MapViewModel(
    private val getMapScene: GetMapSceneUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MapUiState())
    val uiState: StateFlow<MapUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val scene = getMapScene()
            _uiState.update {
                it.copy(
                    isLoading = false,
                    camera = scene.camera,
                    tilesPath = (scene.tiles as? MapTiles.Ready)?.absolutePath,
                    highlights = scene.highlights,
                )
            }
        }
    }
}

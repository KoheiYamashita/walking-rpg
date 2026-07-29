package com.walkingrpg.app.ui.map

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel

/**
 * 地図画面（issue #4 → #10 で成長の抽象レイヤーに）。
 *
 * 役割規約（architecture.md §2）：Composableは描画とイベント送出のみ。
 */
@Composable
fun MapScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MapViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    MapContent(uiState = uiState, onBack = onBack, modifier = modifier)
}

/**
 * 状態を引数で受け取るstatelessな描画本体。
 *
 * インセットの方針：地図そのものは画面いっぱい（ステータスバー／ナビゲーションバーの
 * 下まで）描き、上に重ねるUI部品だけが [safeDrawingPadding] でシステムUIを避ける。
 */
@Composable
fun MapContent(
    uiState: MapUiState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        if (uiState.isLoading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        } else {
            MapCanvas(
                camera = uiState.camera,
                highlights = uiState.highlights,
                userLocation = uiState.userLocation,
                followUserLocation = uiState.isFollowingUser,
                modifier = Modifier.fillMaxSize(),
            )
        }

        FilledTonalButton(
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.TopStart)
                .safeDrawingPadding()
                .padding(8.dp),
        ) {
            Text("← 戻る")
        }
    }
}

package com.walkingrpg.app.ui.map

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel

/**
 * 地図画面（issue #4 スパイク）。
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

/** 状態を引数で受け取るstatelessな描画本体。 */
@Composable
fun MapContent(
    uiState: MapUiState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        TextButton(onClick = onBack) { Text("← 戻る") }

        Box(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            if (uiState.isLoading) {
                CircularProgressIndicator()
            } else {
                MapCanvas(
                    camera = uiState.camera,
                    highlights = uiState.highlights,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

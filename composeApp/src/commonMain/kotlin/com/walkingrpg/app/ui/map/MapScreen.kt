package com.walkingrpg.app.ui.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
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
            when {
                uiState.isLoading -> CircularProgressIndicator()

                uiState.tilesPath == null -> TilesMissingNotice()

                else -> MapCanvas(
                    camera = uiState.camera,
                    tilesPath = uiState.tilesPath,
                    highlights = uiState.highlights,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/** タイル未同梱時の案内。生成手順は scripts/README.md にある。 */
@Composable
private fun TilesMissingNotice(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "ローカル地図タイルがありません",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = "scripts/generate-tiles.sh でPMTilesを生成し、" +
                "composeApp/src/androidMain/assets/map/ に置いてビルドしてください。",
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )
    }
}

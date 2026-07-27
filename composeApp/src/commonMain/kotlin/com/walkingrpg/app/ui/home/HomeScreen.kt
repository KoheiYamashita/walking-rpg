package com.walkingrpg.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel

/**
 * ホーム画面（雛形）。
 *
 * 役割規約（architecture.md §2）：Composableは描画とイベント送出のみ。
 * 状態は持たない（`remember` はUI都合の一時状態だけ）。
 */
@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    HomeContent(
        uiState = uiState,
        onToggleWalk = viewModel::onToggleWalk,
        modifier = modifier,
    )
}

/** 状態を引数で受け取るstatelessな描画本体（プレビュー・テストしやすくするため分離）。 */
@Composable
fun HomeContent(
    uiState: HomeUiState,
    onToggleWalk: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (uiState.isLoading) {
            CircularProgressIndicator()
            return@Column
        }

        Text(text = "walking-rpg", style = MaterialTheme.typography.headlineMedium)
        Text(text = uiState.platformName, style = MaterialTheme.typography.bodyMedium)
        Button(onClick = onToggleWalk) {
            Text(if (uiState.isWalking) "散歩を終える" else "散歩に出る")
        }
    }
}

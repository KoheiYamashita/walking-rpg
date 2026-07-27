package com.walkingrpg.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.walkingrpg.shared.domain.walk.LocationPermissionStatus
import com.walkingrpg.shared.domain.walk.WalkRecordingSnapshot
import com.walkingrpg.shared.domain.walk.WalkSessionSummary
import org.koin.compose.viewmodel.koinViewModel

/**
 * ホーム画面。
 *
 * 役割規約（architecture.md §2）：Composableは描画とイベント送出のみ。
 * 状態は持たない（`remember` はUI都合の一時状態だけ）。
 *
 * issue #2 のスパイクとして、記録中は計測値（経過時間・サンプル数・最新精度）を
 * そのまま画面に出す。本編では出さない情報（design.md §3「画面に出続けるのは最小限」）。
 */
@Composable
fun HomeScreen(
    onOpenMap: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.onScreenResumed() }

    HomeContent(
        uiState = uiState,
        onToggleWalk = viewModel::onToggleWalk,
        onRequestPermission = viewModel::onRequestPermission,
        onExportSession = viewModel::onExportSession,
        onMessageShown = viewModel::onMessageShown,
        onOpenMap = onOpenMap,
        modifier = modifier,
    )
}

/** 状態を引数で受け取るstatelessな描画本体（プレビュー・テストしやすくするため分離）。 */
@Composable
fun HomeContent(
    uiState: HomeUiState,
    onToggleWalk: () -> Unit,
    onRequestPermission: () -> Unit,
    onExportSession: (Long) -> Unit,
    onMessageShown: () -> Unit,
    onOpenMap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 画面ON維持は画面遷移で切れないよう App() で一元管理している（KeepScreenOn）。
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(uiState.message) {
        val message = uiState.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        onMessageShown()
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        // edge-to-edge表示なので、内容がステータスバー／ナビゲーションバー／
        // ディスプレイカットアウトに被らないようインセットを明示する。
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { padding ->
        if (uiState.isLoading) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                Text(text = "walking-rpg", style = MaterialTheme.typography.headlineMedium)
            }
            item {
                Text(text = uiState.platformName, style = MaterialTheme.typography.bodySmall)
            }

            if (uiState.needsPermission) {
                item {
                    PermissionCard(
                        status = uiState.permission,
                        onRequestPermission = onRequestPermission,
                    )
                }
            }

            item {
                RecordingCard(recording = uiState.recording)
            }

            item {
                Button(
                    onClick = onToggleWalk,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (uiState.isWalking) "散歩を終える" else "散歩に出る")
                }
            }

            item {
                TextButton(onClick = onOpenMap, modifier = Modifier.fillMaxWidth()) {
                    Text("地図を見る")
                }
            }

            item {
                HorizontalDivider()
            }
            item {
                Text(
                    text = "記録したセッション（${uiState.sessions.size}件）",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            item {
                Text(
                    text = "エクスポートしたJSONには実際に歩いた位置（自宅を含む）が入ります。" +
                        "共有先の取り扱いに注意してください。",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                )
            }

            if (uiState.sessions.isEmpty()) {
                item {
                    Text(
                        text = "まだ記録がありません",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            items(uiState.sessions, key = { it.session.id }) { summary ->
                SessionRow(summary = summary, onExport = { onExportSession(summary.session.id) })
            }
        }
    }
}

@Composable
private fun PermissionCard(
    status: LocationPermissionStatus,
    onRequestPermission: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = "位置情報の権限が必要です", style = MaterialTheme.typography.titleSmall)
            Text(
                text = when (status) {
                    LocationPermissionStatus.DENIED ->
                        "拒否されています。ダイアログが出ない場合は、端末の「設定 → アプリ → " +
                            "walking-rpg → 権限 → 位置情報」で「アプリの使用中のみ許可」を選んでください。"

                    else -> "散歩の記録には位置情報の権限（正確な位置）が必要です。"
                },
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = onRequestPermission) {
                Text("権限を許可する")
            }
        }
    }
}

/** スパイクの計測表示（経過時間・サンプル数・最新精度・最後に取れてからの経過）。 */
@Composable
private fun RecordingCard(recording: WalkRecordingSnapshot) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = if (recording.isRecording) "記録中" else "停止中",
                style = MaterialTheme.typography.titleMedium,
            )
            MetricRow("経過時間", formatElapsed(recording.elapsedMs))
            MetricRow("サンプル数", "${recording.sampleCount}")
            MetricRow(
                label = "最新の精度",
                value = recording.lastAccuracyMeters?.let(::formatMeters) ?: "-",
            )
            MetricRow(
                label = "最終取得から",
                value = recording.lastSampleAgeMs?.let { formatElapsed(it) } ?: "-",
            )
            if (recording.isStalled()) {
                Text(
                    text = "サンプルが途切れています",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            recording.error?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun MetricRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
        Text(text = value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun SessionRow(summary: WalkSessionSummary, onExport: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = "#${summary.session.id}  ${formatTimestamp(summary.session.startedAtMs)}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = buildString {
                        append("${summary.sampleCount} サンプル / ")
                        val endedAt = summary.session.endedAtMs
                        if (endedAt == null) {
                            append("記録中")
                        } else {
                            append(formatElapsed(endedAt - summary.session.startedAtMs))
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            OutlinedButton(onClick = onExport) {
                Text("書き出す")
            }
        }
    }
}

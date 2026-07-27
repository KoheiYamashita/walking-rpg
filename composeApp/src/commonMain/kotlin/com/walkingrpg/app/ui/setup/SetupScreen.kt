package com.walkingrpg.app.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.walkingrpg.shared.domain.setup.HOME_BLUR_RADIUS_CHOICES
import com.walkingrpg.shared.domain.setup.LlmFormat
import com.walkingrpg.shared.domain.setup.SetupStep
import com.walkingrpg.shared.domain.setup.WeatherProviderChoice
import com.walkingrpg.shared.domain.walk.LocationPermissionStatus
import org.koin.compose.viewmodel.koinViewModel

/**
 * 初回セットアップのウィザード（issue #6）。
 *
 * design.md §9 の決定事項どおり、**LLMの疎通が通るまで先へ進めない**。
 * UIは凝らずMaterial3の素直な部品だけで作る（本編のUIは後続issue）。
 *
 * 役割規約（architecture.md §2）：Composableは描画とイベント送出のみ。
 */
@Composable
fun SetupScreen(
    onCompleted: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SetupViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.onScreenResumed() }

    SetupContent(
        uiState = uiState,
        callbacks = SetupCallbacks(
            onNext = viewModel::onNext,
            onBack = viewModel::onBack,
            onLlmFormatSelected = viewModel::onLlmFormatSelected,
            onBaseUrlChanged = viewModel::onBaseUrlChanged,
            onModelChanged = viewModel::onModelChanged,
            onApiKeyChanged = viewModel::onApiKeyChanged,
            onTestLlmConnection = viewModel::onTestLlmConnection,
            onWeatherProviderSelected = viewModel::onWeatherProviderSelected,
            onWeatherApiKeyChanged = viewModel::onWeatherApiKeyChanged,
            onRequestPermission = viewModel::onRequestPermission,
            onBlurRadiusSelected = viewModel::onBlurRadiusSelected,
            onRegisterHome = viewModel::onRegisterHome,
            onImportArea = viewModel::onImportArea,
            onFinish = { viewModel.onFinish(onCompleted) },
        ),
        modifier = modifier,
    )
}

/** 画面から送出するイベント一式（引数が増えすぎるのでまとめる）。 */
data class SetupCallbacks(
    val onNext: () -> Unit,
    val onBack: () -> Unit,
    val onLlmFormatSelected: (LlmFormat) -> Unit,
    val onBaseUrlChanged: (String) -> Unit,
    val onModelChanged: (String) -> Unit,
    val onApiKeyChanged: (String) -> Unit,
    val onTestLlmConnection: () -> Unit,
    val onWeatherProviderSelected: (WeatherProviderChoice) -> Unit,
    val onWeatherApiKeyChanged: (String) -> Unit,
    val onRequestPermission: () -> Unit,
    val onBlurRadiusSelected: (Int) -> Unit,
    val onRegisterHome: () -> Unit,
    val onImportArea: () -> Unit,
    val onFinish: () -> Unit,
)

/** 状態を引数で受け取るstatelessな描画本体。 */
@Composable
fun SetupContent(
    uiState: SetupUiState,
    callbacks: SetupCallbacks,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
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

        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            LinearProgressIndicator(
                progress = { (uiState.step.ordinal + 1f) / SetupStep.entries.size },
                modifier = Modifier.fillMaxWidth(),
            )

            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item {
                    Text(
                        text = uiState.step.title(),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                }
                item {
                    when (uiState.step) {
                        SetupStep.WELCOME -> WelcomeStep()
                        SetupStep.LLM -> LlmStep(uiState, callbacks)
                        SetupStep.WEATHER -> WeatherStep(uiState, callbacks)
                        SetupStep.HOME -> HomeStep(uiState, callbacks)
                        SetupStep.AREA -> AreaStep(uiState, callbacks)
                        SetupStep.DONE -> DoneStep()
                    }
                }
            }

            HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (uiState.step != SetupStep.WELCOME) {
                    OutlinedButton(onClick = callbacks.onBack) { Text("戻る") }
                }
                if (uiState.step == SetupStep.DONE) {
                    Button(
                        onClick = callbacks.onFinish,
                        enabled = uiState.canAdvance,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("はじめる")
                    }
                } else {
                    Button(
                        onClick = callbacks.onNext,
                        enabled = uiState.canAdvance,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("次へ")
                    }
                }
            }
        }
    }
}

private fun SetupStep.title(): String = when (this) {
    SetupStep.WELCOME -> "ようこそ"
    SetupStep.LLM -> "LLMの接続設定"
    SetupStep.WEATHER -> "天候データの取得元"
    SetupStep.HOME -> "位置情報と自宅"
    SetupStep.AREA -> "対象圏の取り込み"
    SetupStep.DONE -> "準備完了"
}

@Composable
private fun WelcomeStep() {
    Text(
        text = "散歩を記録して育てるゲームです。はじめる前に、次の4つを設定します。\n\n" +
            "1. LLMの接続（APIキーはこの端末にだけ保存します）\n" +
            "2. 天候データの取得元\n" +
            "3. 位置情報の権限と自宅の登録\n" +
            "4. 歩く範囲（対象圏）の地図データ取り込み\n\n" +
            "設定はあとから変更できます。",
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun LlmStep(uiState: SetupUiState, callbacks: SetupCallbacks) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "APIキーは端末のセキュアストレージに保存し、バックアップにも乗せません。" +
                "疎通確認が通るまで先へは進めません。",
            style = MaterialTheme.typography.bodySmall,
        )

        Text(text = "フォーマット", style = MaterialTheme.typography.titleSmall)
        Column(Modifier.selectableGroup()) {
            LlmFormat.entries.forEach { format ->
                OptionRow(
                    label = format.displayName,
                    selected = uiState.llmFormat == format,
                    onSelect = { callbacks.onLlmFormatSelected(format) },
                )
            }
        }

        OutlinedTextField(
            value = uiState.baseUrl,
            onValueChange = callbacks.onBaseUrlChanged,
            label = { Text("ベースURL") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = uiState.model,
            onValueChange = callbacks.onModelChanged,
            label = { Text("モデル名") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = uiState.apiKey,
            onValueChange = callbacks.onApiKeyChanged,
            label = { Text("APIキー") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )

        Button(
            onClick = callbacks.onTestLlmConnection,
            enabled = !uiState.isTestingLlm,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (uiState.isTestingLlm) "確認中…" else "疎通を確認する")
        }

        uiState.llmError?.let { error ->
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (uiState.progress.llmVerified) {
            Text(text = "疎通を確認しました。", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun WeatherStep(uiState: SetupUiState, callbacks: SetupCallbacks) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "散歩の天候は帰宅後にまとめて取得します。既定のOpen-Meteoはキー不要なので、" +
                "こだわりがなければそのまま次へ進めます。",
            style = MaterialTheme.typography.bodySmall,
        )
        Column(Modifier.selectableGroup()) {
            WeatherProviderChoice.entries.forEach { provider ->
                OptionRow(
                    label = provider.displayName,
                    selected = uiState.weatherProvider == provider,
                    onSelect = { callbacks.onWeatherProviderSelected(provider) },
                )
            }
        }
        if (uiState.weatherNeedsApiKey) {
            OutlinedTextField(
                value = uiState.weatherApiKey,
                onValueChange = callbacks.onWeatherApiKeyChanged,
                label = { Text("APIキー") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        uiState.weatherError?.let { error ->
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun HomeStep(uiState: SetupUiState, callbacks: SetupCallbacks) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "散歩の記録には位置情報の権限が必要です。自宅を登録すると、" +
                "自宅の周りをぼかして扱えます。座標はこの端末から出ません。",
            style = MaterialTheme.typography.bodySmall,
        )

        if (uiState.needsPermission) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = when (uiState.permission) {
                            LocationPermissionStatus.DENIED ->
                                "拒否されています。ダイアログが出ない場合は、端末の「設定 → アプリ → " +
                                    "walking-rpg → 権限 → 位置情報」から許可してください。"

                            else -> "位置情報（正確な位置）の権限を許可してください。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = callbacks.onRequestPermission) {
                        Text("権限を許可する")
                    }
                }
            }
        }

        Text(text = "自宅周辺をぼかす半径", style = MaterialTheme.typography.titleSmall)
        Column(Modifier.selectableGroup()) {
            HOME_BLUR_RADIUS_CHOICES.forEach { meters ->
                OptionRow(
                    label = "${meters}m",
                    selected = uiState.homeBlurRadiusMeters == meters,
                    onSelect = { callbacks.onBlurRadiusSelected(meters) },
                )
            }
        }

        Button(
            onClick = callbacks.onRegisterHome,
            enabled = !uiState.isRegisteringHome,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (uiState.isRegisteringHome) "取得中…" else "現在地を自宅として登録")
        }

        if (uiState.homeRegistered) {
            Text(text = "自宅を登録しました。", style = MaterialTheme.typography.bodyMedium)
        }
        uiState.homeError?.let { error ->
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Text(
            text = "自宅はあとで設定画面から登録・変更できます。",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun AreaStep(uiState: SetupUiState, callbacks: SetupCallbacks) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "いまいる場所を中心に半径500mの地図データ（道とスポット）を取り込みます。" +
                "位置情報の権限と通信が必要です。",
            style = MaterialTheme.typography.bodySmall,
        )

        Button(
            onClick = callbacks.onImportArea,
            enabled = !uiState.isImporting,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (uiState.isImporting) "取り込み中…" else "対象圏を取り込む")
        }

        uiState.importResult?.let { result ->
            Text(
                text = "道 ${result.wayCount}件 / スポット ${result.poiCount}件 を取り込みました。",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        uiState.importError?.let { error ->
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun DoneStep() {
    Text(
        text = "設定はここまでです。「はじめる」を押すとホーム画面に移ります。",
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun OptionRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
    }
}

package com.walkingrpg.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import com.walkingrpg.app.ui.components.MetricRow
import com.walkingrpg.app.ui.components.OptionRow
import com.walkingrpg.shared.domain.setup.HOME_BLUR_RADIUS_CHOICES
import com.walkingrpg.shared.domain.setup.LlmFormat
import com.walkingrpg.shared.domain.setup.WeatherProviderChoice
import com.walkingrpg.shared.domain.walk.LocationPermissionStatus
import org.koin.compose.viewmodel.koinViewModel

/**
 * 設定画面（issue #20・design.md §9）。
 *
 * 単一スクロール＋セクション構成（LLM / 天候 / 自宅 / 対象圏 / バックアップ / デバッグ情報）。
 * ナビゲーションライブラリは入れないので、画面内に階層を作らない
 * （`App()` の `BackHandler` が見るのは1階層だけ）。
 *
 * 役割規約（architecture.md §2）：Composableは描画とイベント送出のみ。
 *
 * @param debugInfo `AppViewModel` が持っている「直近の失敗理由」。
 *  ここで持ち直さず引数で流し込む（二重管理を避ける。[SettingsDebugInfo]）。
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    debugInfo: SettingsDebugInfo,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.onScreenResumed() }

    SettingsContent(
        uiState = uiState,
        debugInfo = debugInfo,
        callbacks = SettingsCallbacks(
            onBack = onBack,
            onLlmFormatSelected = viewModel::onLlmFormatSelected,
            onBaseUrlChanged = viewModel::onBaseUrlChanged,
            onModelChanged = viewModel::onModelChanged,
            onApiKeyChanged = viewModel::onApiKeyChanged,
            onTestAndSaveLlm = viewModel::onTestAndSaveLlm,
            onWeatherProviderSelected = viewModel::onWeatherProviderSelected,
            onWeatherApiKeyChanged = viewModel::onWeatherApiKeyChanged,
            onSaveWeather = viewModel::onSaveWeather,
            onRequestPermission = viewModel::onRequestPermission,
            onBlurRadiusSelected = viewModel::onBlurRadiusSelected,
            onRegisterHome = viewModel::onRegisterHome,
            onReimportOsmArea = viewModel::onReimportOsmArea,
            onExportBackup = viewModel::onExportBackup,
            onImportBackupRequested = viewModel::onImportBackupRequested,
            onImportBackupConfirmed = viewModel::onImportBackupConfirmed,
            onImportBackupDismissed = viewModel::onImportBackupDismissed,
        ),
        modifier = modifier,
    )
}

/**
 * 「何も起きなかったように見える」のを避けるための表示用の値（`AppViewModel` の3本）。
 *
 * どれも握りつぶして良い失敗（次の起動・次の散歩でやり直せる）だが、
 * 原因を見る場所がどこにも無いのは別の問題なので、ここに出す。
 */
data class SettingsDebugInfo(
    val recomputeError: String? = null,
    val weatherFetchError: String? = null,
    val llmGenerationError: String? = null,
) {
    val hasAny: Boolean
        get() = recomputeError != null || weatherFetchError != null || llmGenerationError != null
}

/** 画面から送出するイベント一式（引数が増えすぎるのでまとめる）。 */
data class SettingsCallbacks(
    val onBack: () -> Unit,
    val onLlmFormatSelected: (LlmFormat) -> Unit,
    val onBaseUrlChanged: (String) -> Unit,
    val onModelChanged: (String) -> Unit,
    val onApiKeyChanged: (String) -> Unit,
    val onTestAndSaveLlm: () -> Unit,
    val onWeatherProviderSelected: (WeatherProviderChoice) -> Unit,
    val onWeatherApiKeyChanged: (String) -> Unit,
    val onSaveWeather: () -> Unit,
    val onRequestPermission: () -> Unit,
    val onBlurRadiusSelected: (Int) -> Unit,
    val onRegisterHome: () -> Unit,
    val onReimportOsmArea: () -> Unit,
    val onExportBackup: () -> Unit,
    val onImportBackupRequested: () -> Unit,
    val onImportBackupConfirmed: () -> Unit,
    val onImportBackupDismissed: () -> Unit,
)

/** 状態を引数で受け取るstatelessな描画本体。 */
@Composable
fun SettingsContent(
    uiState: SettingsUiState,
    debugInfo: SettingsDebugInfo,
    callbacks: SettingsCallbacks,
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

        // 破壊的操作の確認（issue #18）。状態はUiState側にあるので remember を持たない
        if (uiState.backupImportConfirming) {
            ImportBackupConfirmDialog(callbacks)
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Text(text = "設定", style = MaterialTheme.typography.headlineSmall)
            }

            item { SectionTitle("LLMの接続") }
            item { LlmSection(uiState, callbacks) }

            item { HorizontalDivider() }
            item { SectionTitle("天候データの取得元") }
            item { WeatherSection(uiState, callbacks) }

            item { HorizontalDivider() }
            item { SectionTitle("自宅") }
            item { HomeSection(uiState, callbacks) }

            item { HorizontalDivider() }
            item { SectionTitle("対象圏の地図データ") }
            item { AreaSection(uiState, callbacks) }

            item { HorizontalDivider() }
            item { SectionTitle("バックアップ") }
            item { BackupSection(uiState, callbacks) }

            item { HorizontalDivider() }
            item { SectionTitle("デバッグ情報") }
            item { DebugSection(debugInfo) }

            item {
                TextButton(onClick = callbacks.onBack, modifier = Modifier.fillMaxWidth()) {
                    Text("ホームへ戻る")
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text = text, style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun ErrorText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
    )
}

/**
 * LLM接続の現在値＋編集フォーム。
 *
 * **キーは伏せる**（「保存済み」かどうかだけ出す）。保存は「接続テストして保存」の
 * 1本だけで、疎通が通らなければ保存されない（`TestLlmConnectionUseCase`）。
 * 削除の口は置かない（`SettingsViewModel` のKDoc）。
 */
@Composable
private fun LlmSection(uiState: SettingsUiState, callbacks: SettingsCallbacks) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                val saved = uiState.savedLlmSummary
                if (saved == null) {
                    Text(
                        text = "まだ保存されていません。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    MetricRow("フォーマット", saved.format.displayName)
                    MetricRow("ベースURL", saved.baseUrl)
                    MetricRow("モデル名", saved.model)
                    MetricRow("APIキー", if (saved.hasApiKey) "保存済み（***）" else "未設定")
                }
            }
        }

        Text(
            text = "変更するときは、新しい値で接続テストを通してください。" +
                "疎通が通ったときだけ保存されます（通らない設定は残しません）。",
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
            onClick = callbacks.onTestAndSaveLlm,
            enabled = !uiState.isTestingLlm,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (uiState.isTestingLlm) "確認中…" else "接続テストして保存")
        }

        if (uiState.llmSaved) {
            Text(text = "疎通を確認して保存しました。", style = MaterialTheme.typography.bodyMedium)
        }
        uiState.llmError?.let { ErrorText(it) }
    }
}

/**
 * 天候プロバイダの選択。疎通テストは持たない（形式チェックのみ）。
 * 実際に叩いて確かめるのは #11 の Provider 実装の領分。
 */
@Composable
private fun WeatherSection(uiState: SettingsUiState, callbacks: SettingsCallbacks) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "散歩の天候は帰宅後にまとめて取得します。既定のOpen-Meteoはキー不要です。",
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
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Button(
            onClick = callbacks.onSaveWeather,
            enabled = !uiState.isSavingWeather,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (uiState.isSavingWeather) "保存中…" else "天候の設定を保存")
        }
        if (uiState.weatherSaved) {
            Text(text = "保存しました。", style = MaterialTheme.typography.bodyMedium)
        }
        uiState.weatherError?.let { ErrorText(it) }
    }
}

/**
 * 自宅の再設定。
 *
 * **座標の入力欄は作らない・登録済みの座標も表示しない**
 * （CONTRIBUTING.md「位置情報の扱い」）。出すのは「登録済み/未登録」とぼかし半径だけ。
 */
@Composable
private fun HomeSection(uiState: SettingsUiState, callbacks: SettingsCallbacks) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        MetricRow("登録状態", if (uiState.homeRegistered) "登録済み" else "未登録")
        Text(
            text = "自宅の座標は端末内にだけ保存し、画面にも出しません。" +
                "登録し直すときは、自宅に居る状態でボタンを押してください。",
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

        // 記録側（WalkRecorder）は散歩の開始時に自宅を1回だけ読むので、
        // 記録中の散歩には反映されない。
        Text(
            text = "変更は次の散歩から有効になります（記録中の散歩には反映されません）。",
            style = MaterialTheme.typography.bodySmall,
        )

        uiState.homeNotice?.let {
            Text(text = it, style = MaterialTheme.typography.bodyMedium)
        }
        uiState.homeError?.let { ErrorText(it) }
    }
}

/**
 * 対象圏の再取り込み（ホームのデバッグカードから移設）。
 *
 * 取り込みは図鑑進捗の作り直しまで含めて1本（`ReimportOsmAreaUseCase`）。
 * 新しく入ったスポットの文章は次回の生成で埋まるので、ここから生成は叩かない。
 */
@Composable
private fun AreaSection(uiState: SettingsUiState, callbacks: SettingsCallbacks) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "いまいる場所を中心に半径500mの地図データ（道とスポット）を取り込み直します。" +
                "位置情報の権限と通信が必要です。取り込みは何度でもやり直せます（件数は増えません）。",
            style = MaterialTheme.typography.bodySmall,
        )

        val counts = uiState.osmCounts
        MetricRow("いまの道", counts?.let { "${it.wayCount}件" } ?: "-")
        MetricRow("いまのスポット", counts?.let { "${it.poiCount}件" } ?: "-")

        uiState.importResult?.let { result ->
            HorizontalDivider()
            MetricRow("取り込んだ道", "${result.wayCount}件")
            MetricRow("取り込んだスポット", "${result.poiCount}件")
            MetricRow(
                label = "除外（道 / スポット）",
                value = "${result.excludedWayCount} / ${result.excludedPoiCount}件",
            )
            Text(
                text = "スポットの除外内訳：配置禁止 ${result.excludedUnsafePoiCount}件、" +
                    "分類対象外 ${result.excludedUnclassifiedPoiCount}件",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = "新しく入ったスポットの文章は、次回の生成でまとめて用意されます。",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Button(
            onClick = callbacks.onReimportOsmArea,
            enabled = !uiState.isImporting,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (uiState.isImporting) "取り込み中…" else "対象圏を取り込み直す")
        }

        uiState.importError?.let { ErrorText(it) }
    }
}

/**
 * バックアップ（design.md §9「手動エクスポート/インポート」・architecture.md §6）。
 *
 * OS自動バックアップは設定済み（`backup_rules.xml` / `data_extraction_rules.xml`）なので、
 * ここに出すのは手動の2本だけ。**インポートは破壊的なので確認ダイアログを必ず挟む**
 * （ダイアログ本体は [SettingsContent] 側。押した瞬間に走る導線を作らない）。
 */
@Composable
private fun BackupSection(uiState: SettingsUiState, callbacks: SettingsCallbacks) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "散歩の記録・アルバムは端末のDBと画像で、OS標準の自動バックアップの対象です。" +
                "機種変更や念のための保管には、下のエクスポートでzipを書き出してください" +
                "（インポートで元の状態に戻せます）。",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            text = "書き出すzipには散歩の位置情報（自宅を含む）が入ります。保存先の選択にご注意ください。",
            style = MaterialTheme.typography.bodySmall,
        )

        Button(
            onClick = callbacks.onExportBackup,
            enabled = !uiState.isExportingBackup && !uiState.isImportingBackup,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (uiState.isExportingBackup) "書き出し中…" else "エクスポート（zipを共有）")
        }

        uiState.backupExportResult?.let { result ->
            Text(
                text = "${result.fileName} を書き出しました（${result.byteSize / 1024}KB）。",
                style = MaterialTheme.typography.bodyMedium,
            )
            MetricRow("散歩", "${result.counts.sessionCount}件")
            MetricRow("測位サンプル", "${result.counts.sampleCount}件")
            MetricRow("アルバム", "${result.counts.snapshotCount}ヶ月")
        }
        uiState.backupExportError?.let { ErrorText(it) }

        HorizontalDivider()

        Text(
            text = "インポートすると、いまの記録はすべてzipの中身に置き換わります。" +
                "先にエクスポートしておくと元に戻せます。",
            style = MaterialTheme.typography.bodySmall,
        )
        Button(
            onClick = callbacks.onImportBackupRequested,
            enabled = !uiState.isExportingBackup && !uiState.isImportingBackup,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (uiState.isImportingBackup) "取り込み中…" else "インポート（zipから復元）")
        }

        uiState.backupImportResult?.let { result ->
            Text(
                text = "取り込みました（散歩${result.counts.sessionCount}件・" +
                    "アルバム${result.counts.snapshotCount}ヶ月）。",
                style = MaterialTheme.typography.bodyMedium,
            )
            // 自宅はセキュアストレージ側＝バックアップの対象外。必ず伝える
            Text(text = result.notice, style = MaterialTheme.typography.bodyMedium)
        }
        uiState.backupImportError?.let { ErrorText(it) }
    }
}

/**
 * インポートの確認。**取り返しがつかない操作なので、既定の選択は「やめる」側に置く**
 * （`dismissButton` ではなく確認ボタンを明示的に押させる）。
 */
@Composable
private fun ImportBackupConfirmDialog(callbacks: SettingsCallbacks) {
    AlertDialog(
        onDismissRequest = callbacks.onImportBackupDismissed,
        title = { Text("バックアップから復元しますか？") },
        text = {
            Text(
                "現在の記録はすべて置き換えられます（散歩・通過・歩数・天候・" +
                    "パートナーの発話履歴・アルバム）。この操作は取り消せません。\n\n" +
                    "続けると、ファイルの選択画面が開きます。",
            )
        },
        confirmButton = {
            TextButton(onClick = callbacks.onImportBackupConfirmed) {
                Text("置き換える")
            }
        },
        dismissButton = {
            TextButton(onClick = callbacks.onImportBackupDismissed) {
                Text("やめる")
            }
        },
    )
}

/** 直近の失敗理由。何も起きていなければ「異常なし」だけを出す。 */
@Composable
private fun DebugSection(debugInfo: SettingsDebugInfo) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!debugInfo.hasAny) {
            Text(
                text = "直近の集計・天候取得・文章生成はいずれも失敗していません。",
                style = MaterialTheme.typography.bodySmall,
            )
            return@Column
        }
        Text(
            text = "どれも次の起動・次の散歩でやり直されるので、記録そのものは欠けません。",
            style = MaterialTheme.typography.bodySmall,
        )
        debugInfo.recomputeError?.let {
            MetricRow("散歩の集計", "失敗")
            ErrorText(it)
        }
        debugInfo.weatherFetchError?.let {
            MetricRow("天候の取得", "失敗")
            ErrorText(it)
        }
        debugInfo.llmGenerationError?.let {
            MetricRow("文章の生成", "失敗")
            ErrorText(it)
        }
    }
}

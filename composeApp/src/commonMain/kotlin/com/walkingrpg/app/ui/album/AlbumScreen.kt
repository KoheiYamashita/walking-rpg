package com.walkingrpg.app.ui.album

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.walkingrpg.app.ui.review.formatDistanceKm
import com.walkingrpg.shared.domain.snapshot.MonthlySnapshotStats
import org.koin.compose.viewmodel.koinViewModel

/**
 * アルバム画面（issue #17・design.md §4.5「その月の地図を1枚の画像として永久保存」）。
 *
 * 役割規約（architecture.md §2）：Composableは描画とイベント送出のみ。
 *
 * ## 出すもの
 * 月ごとに「絵＋1行の数値」。カードをタップすると同じ画面の中で拡大表示に切り替わる
 * （一覧⇄詳細を画面内の `remember` で持つのは `CodexScreen` の前例に倣う）。
 *
 * ## 出さないもの
 * - **パートナーの語り**（Wrapped 的な文章）。数値を `stats_json` に焼くところまでが
 *   issue #17 のスコープで、語りは後続（design.md §4.5「パートナーが語る形式にする」）
 * - **月ごとの比較・増減**。「先月より減った」は design.md §2「時間経過では悪化させない」に
 *   反する見せ方で、アルバムは並べて眺めるものであって成績表ではない
 */
@Composable
fun AlbumScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AlbumViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    AlbumContent(uiState = uiState, onBack = onBack, modifier = modifier)
}

/**
 * 状態を引数で受け取るstatelessな描画本体（プレビュー・テストしやすくするため分離）。
 *
 * **拡大表示から一覧への戻りは自分で持つ**：`App()` の `BackHandler` は
 * 「ホームかどうか」の1階層しか見ていないので、`CodexScreen` と同じく画面内の
 * 「← 一覧へ」で補う（ナビゲーションライブラリを入れるとき＝issue #20以降に整理する）。
 */
@Composable
fun AlbumContent(
    uiState: AlbumUiState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedMonth by remember { mutableStateOf<String?>(null) }
    val selected = uiState.months.firstOrNull { it.month == selectedMonth }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        // edge-to-edge表示なので、内容がステータスバー等に被らないようインセットを明示する。
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

        if (selected != null) {
            AlbumDetail(
                month = selected,
                onBackToList = { selectedMonth = null },
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            )
            return@Scaffold
        }

        AlbumList(
            months = uiState.months,
            onSelect = { month -> selectedMonth = month.month },
            onBack = onBack,
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
        )
    }
}

@Composable
private fun AlbumList(
    months: List<AlbumMonthUiState>,
    onSelect: (AlbumMonthUiState) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(text = "アルバム", style = MaterialTheme.typography.headlineMedium)
        }

        if (months.isEmpty()) {
            item {
                Text(
                    text = "まだ1枚もありません。月をまたぐと、その月の街の姿が1枚ぶん増えます。",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        items(months, key = { it.month }) { month ->
            AlbumRow(month = month, onClick = { onSelect(month) })
        }

        item {
            TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                Text("← 戻る")
            }
        }
    }
}

/** 一覧の1枚（サムネイル＋月＋数値の1行）。 */
@Composable
private fun AlbumRow(month: AlbumMonthUiState, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SnapshotThumbnail(month = month)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = formatMonthLabel(month.month),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = formatMonthSummary(month.stats),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * サムネイル。画像が読めなければ枠だけ出す（数値のカードとしては成立する）。
 *
 * スナップショットは正方形（`ComposeMonthlySnapshotRenderer`）なので、
 * ここも正方形の枠で受ける＝縦横比が崩れない。
 */
@Composable
private fun SnapshotThumbnail(month: AlbumMonthUiState) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val image = month.image
        if (image == null) {
            Text(
                text = "—",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Image(
                bitmap = image,
                contentDescription = "${formatMonthLabel(month.month)}の街",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

/**
 * 1枚の拡大表示。
 *
 * 出すのは絵と数値だけ（上の「出さないもの」）。長文の出口は振り返りと図鑑に
 * 集約する決定（design.md §4.5）なので、ここに解説を足さない。
 */
@Composable
private fun AlbumDetail(
    month: AlbumMonthUiState,
    onBackToList: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TextButton(onClick = onBackToList) {
            Text("← 一覧へ")
        }
        Text(text = formatMonthLabel(month.month), style = MaterialTheme.typography.headlineSmall)

        val image = month.image
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (image == null) {
                Text(
                    text = "画像が見つかりません",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Image(
                    bitmap = image,
                    contentDescription = "${formatMonthLabel(month.month)}の街",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            }
        }

        HorizontalDivider()
        Text(text = formatMonthSummary(month.stats), style = MaterialTheme.typography.bodyLarge)
        month.stats.discoveredSpeciesNames
            .takeIf { it.isNotEmpty() }
            ?.let { names ->
                Text(
                    text = "見つけたもの：${names.joinToString("、")}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
    }
}

/**
 * 表示用の整形。UI都合の変換なのでUI層に置く（`ReviewFormatting` と同じ扱い）。
 *
 * 距離の書式は振り返りと同じ [formatDistanceKm] を使い回す：同じ数字が
 * 画面によって「2.3km」「2,340m」と書き分けられると、足し算が合っているのに
 * 合っていないように見える。
 */
internal fun formatMonthSummary(stats: MonthlySnapshotStats): String = buildString {
    append(formatDistanceKm(stats.distanceMeters))
    append("・散歩 ${stats.sessionCount}回")
    append("・新しい道 ${stats.newWayCount}本")
    append("・発見 ${stats.discoveredSpeciesNames.size}")
}

/**
 * 'YYYY-MM' → 「2026年6月」。
 *
 * 形が想定外なら素の文字列を出す（アルバムが開かなくなるより、
 * 見慣れない見出しが1つ出るほうがまし）。
 */
internal fun formatMonthLabel(iso: String): String {
    val year = iso.substringBefore('-').toIntOrNull() ?: return iso
    val month = iso.substringAfter('-').toIntOrNull() ?: return iso
    return "${year}年${month}月"
}

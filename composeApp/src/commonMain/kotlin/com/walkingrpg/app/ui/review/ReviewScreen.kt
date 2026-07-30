package com.walkingrpg.app.ui.review

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.walkingrpg.app.ui.home.formatTimestamp
import com.walkingrpg.shared.domain.codex.Species
import com.walkingrpg.shared.domain.review.WalkReview
import org.koin.compose.viewmodel.koinViewModel

/**
 * 振り返り画面（issue #15・design.md §3 の 17:08「帰宅」・§4.5）。
 *
 * 役割規約（architecture.md §2）：Composableは描画とイベント送出のみ。
 *
 * ## 所要1分で閉じられる密度
 * design.md §3 のウォークスルーがそのまま並び順になっている：
 * おかえり → 今日の道と時間（＋天候） → 育った道 → 図鑑（発見と気配） → パートナーの一言。
 * ボタンは「戻る」だけで、ここから先に進む導線は置かない
 * （**翌日の楽しみを残した状態で閉じる**のが狙い）。
 *
 * ## 出さないもの
 * - 訪問回数・残り回数（design.md §4.4「残り回数は数字で見せず、予兆で匂わせる」）。
 *   図鑑の進捗は `n/17` だけ
 * - 未発見の種の名前。予兆は**予兆文だけ**を出す（`Species.foreshadowText`）
 * - サンプル数・精度のような計測値（あれはスパイクの表示で、振り返りの情報ではない）
 *
 * ## 数値は即時、文章は遅延
 * 一言は生成できていなければ定型文が出る（`ObserveWalkReviewRemarkUseCase`）。
 * 画面には「生成中」の表示を出さない：待つべきものがあるように見せると、
 * 1分で閉じる体験が待ち時間に変わる（architecture.md §5）。
 */
@Composable
fun ReviewScreen(
    sessionId: Long,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ReviewViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // どのセッションを見るかはコンストラクタではなくここで渡す（ReviewViewModel のKDoc）
    LaunchedEffect(sessionId) { viewModel.onSessionSelected(sessionId) }

    ReviewContent(uiState = uiState, onBack = onBack, modifier = modifier)
}

/** 状態を引数で受け取るstatelessな描画本体（プレビュー・テストしやすくするため分離）。 */
@Composable
fun ReviewContent(
    uiState: ReviewUiState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
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

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val review = uiState.review
            if (review == null) {
                item {
                    Text(
                        text = uiState.error ?: "この散歩の記録が見つかりませんでした。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else {
                item { ReviewHeader(review) }
                item { NumbersCard(review) }
                if (review.grownWays.isNotEmpty()) {
                    item { GrownWaysCard(review) }
                }
                item { CodexCard(review) }
                uiState.remark?.let { remark ->
                    item {
                        Text(
                            text = remark.text,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }

            item {
                TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                    Text("← 戻る")
                }
            }
        }
    }
}

@Composable
private fun ReviewHeader(review: WalkReview) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = "おかえりなさい", style = MaterialTheme.typography.headlineMedium)
        Text(
            text = formatTimestamp(review.startedAtMs),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 数値サマリ。**圏外でもここだけで振り返りが成立する**（architecture.md §5）。
 *
 * 天候は後付け取得が済んでいなければ行そのものを出さない（未取得を「不明」と書かない）。
 */
@Composable
private fun NumbersCard(review: WalkReview) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SummaryRow("今日の道", formatDistanceKm(review.distanceMeters))
            SummaryRow("歩いた時間", formatWalkDuration(review.durationMs))
            formatWeather(review.weather)?.let { SummaryRow("天候", it) }
        }
    }
}

/**
 * 今日育った道。多くても3本だけ出す。
 *
 * 1回の散歩で通る道は20〜40本（`GrowthConfig` のKDoc）なので、育った道も
 * 日によっては十数本になる。全部並べると1分では読めないので、**上から3本**にして
 * 残りは件数だけ添える（地図を見れば全部分かる）。
 */
@Composable
private fun GrownWaysCard(review: WalkReview) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(text = "今日育った道", style = MaterialTheme.typography.titleMedium)
            review.grownWays.take(MAX_GROWN_WAY_ROWS).forEach { grown ->
                Text(text = formatGrownWay(grown), style = MaterialTheme.typography.bodyMedium)
            }
            val rest = review.grownWays.size - MAX_GROWN_WAY_ROWS
            if (rest > 0) {
                Text(
                    text = "ほかに ${rest}本",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 図鑑側の今日ぶん。
 *
 * 発見は名前を出す（もう図鑑に載っているので伏せる意味がない）。予兆は
 * **予兆文だけ**＝「さっきの振動の中身」がここで開く（design.md §3 の 17:08
 * 「青い羽根（カワセミの予兆。図鑑にはまだ載らない）」）。種名を出したら予告になる。
 */
@Composable
private fun CodexCard(review: WalkReview) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(text = "図鑑", style = MaterialTheme.typography.titleMedium)

            review.discoveredSpecies.forEach { species ->
                Text(
                    text = "${species.name} に出会った",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            review.approachedSpecies.forEach { species ->
                ForeshadowRow(species)
            }
            if (review.discoveredSpecies.isEmpty() && review.approachedSpecies.isEmpty()) {
                Text(
                    text = "今日は静かだった。",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            SummaryRow("集まった数", "${review.codexDiscoveredCount} / ${review.codexTotalCount}")
        }
    }
}

@Composable
private fun ForeshadowRow(species: Species) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(text = species.foreshadowText, style = MaterialTheme.typography.bodyMedium)
        Text(
            text = "何かが近いのかもしれない",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
        Text(text = value, style = MaterialTheme.typography.bodyLarge)
    }
}

/** 育った道を並べる上限。理由は [GrownWaysCard] のKDoc。 */
private const val MAX_GROWN_WAY_ROWS = 3

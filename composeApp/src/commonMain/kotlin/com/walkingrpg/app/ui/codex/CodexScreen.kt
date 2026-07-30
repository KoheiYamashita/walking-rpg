package com.walkingrpg.app.ui.codex

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.walkingrpg.shared.domain.codex.CodexCategory
import com.walkingrpg.shared.domain.codex.CodexEntry
import com.walkingrpg.shared.domain.codex.CodexSection
import org.koin.compose.viewmodel.koinViewModel

/**
 * 図鑑画面（issue #13・design.md §4.4）。
 *
 * 役割規約（architecture.md §2）：Composableは描画とイベント送出のみ。
 *
 * ## 出すもの・出さないもの
 * - 発見済み：名前と記述文（2〜3文）。design.md §4.5「長文の出口はすべてここに集約する」
 * - 未発見：シルエット（「？」）だけ。予兆が立っている種は控えめな一文を添える
 * - **数字は一切出さない**（訪問回数・残り回数）。UiStateがそもそも持っていない
 *   （[CodexUiState]）ので、ここで出したくても出せない
 *
 * ## イラストを描かない
 * design.md §8「凝らない」に従い、絵は持たない。マスは単色の丸1つで、
 * 発見済みは名前の1文字目、未発見は「？」を置くだけ。
 * 具象イラストは将来の枠（design.md §8「具象イラストは図鑑の中だけ」）で、
 * MVPの検証仮説（塗り＋図鑑＋一言で歩き続けるか）にアート量は要らない。
 */
@Composable
fun CodexScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CodexViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    CodexContent(uiState = uiState, onBack = onBack, modifier = modifier)
}

/**
 * 状態を引数で受け取るstatelessな描画本体（プレビュー・テストしやすくするため分離）。
 *
 * 一覧と詳細の切り替えは `remember` の [selectedSpeciesId] で持つ（UI都合の一時状態＝
 * architecture.md §2「`remember` はUI都合の一時状態だけ」に該当）。
 *
 * **詳細から一覧への戻りは自分で持つ**：`App()` の `BackHandler` は
 * 「ホームかどうか」の1階層しか見ていないので、ここで詳細を開いていても
 * システムバックはホームまで一気に戻ってしまう。それを画面内の「← 一覧へ」で補う
 * （ナビゲーションライブラリを入れるとき＝issue #20以降に整理する）。
 */
@Composable
fun CodexContent(
    uiState: CodexUiState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedSpeciesId by remember { mutableStateOf<String?>(null) }
    val selected = uiState.codex.sections
        .flatMap { it.entries }
        .firstOrNull { it.species.id == selectedSpeciesId }

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
            CodexDetail(
                entry = selected,
                onBackToList = { selectedSpeciesId = null },
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            )
            return@Scaffold
        }

        CodexList(
            sections = uiState.codex.sections,
            // 未発見のマスは開かない：詳細に出せるものが何も無い（名前も記述文も伏せてある）ので、
            // 開けると「押しても何も起きない」だけの操作になる
            onSelect = { entry -> if (entry.isDiscovered) selectedSpeciesId = entry.species.id },
            onBack = onBack,
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
        )
    }
}

@Composable
private fun CodexList(
    sections: List<CodexSection>,
    onSelect: (CodexEntry) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(text = "図鑑", style = MaterialTheme.typography.headlineMedium)
        }

        if (sections.isEmpty()) {
            item {
                Text(
                    text = "まだ何もありません。対象圏のデータを取り込むと、棚が並びます。",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        sections.forEach { section ->
            item(key = "header-${section.category.name}") {
                HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
            }
            item(key = "title-${section.category.name}") {
                Text(
                    text = section.category.label(),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            items(section.entries, key = { it.species.id }) { entry ->
                CodexRow(entry = entry, onClick = { onSelect(entry) })
            }
        }

        item {
            TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                Text("← 戻る")
            }
        }
    }
}

/**
 * 一覧の1マス。
 *
 * 発見済みでも記述文は出さない（1〜2行に収まらないので詳細で開く）。
 * 未発見は名前も伏せる＝ここで名前を出すと「あと何回でカワセミ」が見えてしまい、
 * 予兆の意味がなくなる（design.md §4.4）。
 */
@Composable
private fun CodexRow(entry: CodexEntry, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CodexMark(entry = entry)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = if (entry.isDiscovered) entry.species.name else "？",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (entry.isNewlyDiscovered) FontWeight.Bold else null,
                )
                when {
                    entry.isNewlyDiscovered -> Text(
                        text = "この散歩で見つけた",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )

                    entry.showsForeshadow -> Text(
                        text = entry.species.foreshadowText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * マスの印。単色の丸1つだけ（上記「イラストを描かない」）。
 *
 * 発見済みは名前の1文字目、未発見は「？」。文字だけで差が付くので、
 * 色は発見済み／未発見の2段だけにしてある。
 */
@Composable
private fun CodexMark(entry: CodexEntry) {
    val background = if (entry.isDiscovered) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    Box(
        modifier = Modifier.size(40.dp).clip(CircleShape).background(background),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (entry.isDiscovered) entry.species.name.take(1) else "？",
            style = MaterialTheme.typography.titleMedium,
            color = if (entry.isDiscovered) {
                MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

/**
 * 1種の詳細。開けるのは発見済みだけ（[CodexContent] の `onSelect`）。
 *
 * ここが design.md §4.5「長文の出口はすべてここに集約する」の図鑑側の出口。
 * 予兆の一文も添える：出会う前に何度も見ていた手がかりが、出会ったあとに
 * 「あれはこれだったのか」に変わる（予兆が期待だけで終わらない）。
 */
@Composable
private fun CodexDetail(
    entry: CodexEntry,
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
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CodexMark(entry = entry)
            Column {
                Text(text = entry.species.name, style = MaterialTheme.typography.headlineSmall)
                Text(
                    text = entry.species.category.label(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        entry.description?.let { description ->
            Text(text = description, style = MaterialTheme.typography.bodyLarge)
        }
        HorizontalDivider()
        Text(
            text = "見つける前の気配：${entry.species.foreshadowText}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 棚の見出し。design.md §8 の図鑑カテゴリ体系の呼び名。 */
private fun CodexCategory.label(): String = when (this) {
    CodexCategory.PARK -> "公園"
    CodexCategory.WATER -> "水辺"
    CodexCategory.RAILWAY -> "鉄道"
    CodexCategory.FARMLAND -> "農地"
    CodexCategory.TREE -> "樹木"
    CodexCategory.SHRINE -> "寺社"
    CodexCategory.URBAN -> "市街"
}

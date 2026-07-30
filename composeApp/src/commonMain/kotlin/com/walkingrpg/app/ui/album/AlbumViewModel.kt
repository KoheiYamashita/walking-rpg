package com.walkingrpg.app.ui.album

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.decodeToImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.walkingrpg.shared.domain.snapshot.GetSnapshotAlbumUseCase
import com.walkingrpg.shared.domain.snapshot.MonthlySnapshotStats
import com.walkingrpg.shared.domain.snapshot.ReadSnapshotImageUseCase
import com.walkingrpg.shared.domain.snapshot.Snapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * アルバムの1枚ぶん。
 *
 * `ImageBitmap` はUI層の型なので、`shared` のUiState（存在しない）にも
 * ドメインモデルにも載せない：ここまで来て初めて絵になる
 * （`ReadSnapshotImageUseCase` が返すのはバイト列）。
 *
 * @param image 画像。読めなければ `null`＝数値だけのカードとして出す（縮退）。
 */
data class AlbumMonthUiState(
    val month: String,
    val stats: MonthlySnapshotStats,
    val image: ImageBitmap?,
)

/** アルバム画面の状態。immutableに保ち、画面はこれ1つだけを見て描画する。 */
data class AlbumUiState(
    val isLoading: Boolean = true,
    val months: List<AlbumMonthUiState> = emptyList(),
)

/**
 * アルバム画面のViewModel。
 *
 * 役割規約（architecture.md §2）：UseCaseを呼んで [StateFlow] を組み立てるだけ。
 * ファイルにもDBにも直接触らない（画像の在り処を知っているのは
 * `SnapshotImageStore` の向こう側だけ）。
 *
 * 購読（`Observe...`）ではなく一度だけ読むのは、アルバムが増えるのが
 * **アプリの起動時だけ**（`GenerateMonthlySnapshotsUseCase`）だから：
 * 画面を開いたままアルバムが増える状況が無いので、地図・図鑑のような
 * 再読み込みの経路は要らない。
 */
class AlbumViewModel(
    private val getSnapshotAlbum: GetSnapshotAlbumUseCase,
    private val readSnapshotImage: ReadSnapshotImageUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AlbumUiState())
    val uiState: StateFlow<AlbumUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val months = getSnapshotAlbum().map { snapshot ->
            AlbumMonthUiState(
                month = snapshot.month.iso,
                stats = snapshot.stats,
                image = loadImage(snapshot),
            )
        }
        _uiState.update { it.copy(isLoading = false, months = months) }
    }

    /**
     * 1枚ぶんの画像を開く。読めない・壊れているときは `null`。
     *
     * 例外を握るのは、1枚のファイルが壊れていてもアルバム全体が開かなくなっては
     * ならないから（`ReadSnapshotImageUseCase` のKDoc「null は縮退の合図」）。
     * 数値は `stats_json` 側にあるので、絵の無いカードとしては成立する。
     */
    private suspend fun loadImage(snapshot: Snapshot): ImageBitmap? = try {
        readSnapshotImage(snapshot)?.decodeToImageBitmap()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        null
    }
}

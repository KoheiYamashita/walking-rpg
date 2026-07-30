package com.walkingrpg.app.ui.album

import com.walkingrpg.shared.domain.snapshot.CalendarMonth
import com.walkingrpg.shared.domain.snapshot.GetSnapshotAlbumUseCase
import com.walkingrpg.shared.domain.snapshot.MonthlySnapshotStats
import com.walkingrpg.shared.domain.snapshot.ReadSnapshotImageUseCase
import com.walkingrpg.shared.domain.snapshot.Snapshot
import com.walkingrpg.shared.domain.snapshot.SnapshotRepository
import com.walkingrpg.shared.domain.snapshot.snapshotImagePath
import com.walkingrpg.shared.platform.SnapshotImageStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * アルバム画面の状態の組み立て（issue #17）。
 *
 * 見たいのは2つ：
 * - 保存されている月が新しい順に並び、数値がそのまま乗ること
 * - **画像が読めない月でも縮退して並ぶこと**（数値は `stats_json` 側にあるので
 *   カードとしては成立する。行があるのに画面が空になるのが最悪）
 *
 * 画像が実際に絵になる経路（`decodeToImageBitmap`）はプラットフォームのデコーダを
 * 呼ぶのでJVMのunit testでは確かめられない。ここで見るのは「読めなければ null で通る」側だけ。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AlbumViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        // viewModelScope は Dispatchers.Main を使うのでテスト用に差し替える
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** 読み取りだけを持つ `snapshot`（アルバムは書かない）。 */
    private class FakeSnapshotRepository(
        private val rows: List<Snapshot>,
    ) : SnapshotRepository {

        override suspend fun insertIfAbsent(snapshot: Snapshot): Boolean =
            error("アルバム画面は1枚も作らない")

        override suspend fun snapshot(month: CalendarMonth): Snapshot? =
            rows.firstOrNull { it.month == month }

        // 本実装（SQL の ORDER BY month DESC）と同じ並び
        override suspend fun snapshots(): List<Snapshot> = rows.sortedByDescending { it.month.iso }

        override suspend fun count(): Int = rows.size
    }

    /** 与えたパスのぶんだけ中身を返す画像の置き場。 */
    private class FakeSnapshotImageStore(
        private val files: Map<String, ByteArray> = emptyMap(),
    ) : SnapshotImageStore {
        override suspend fun write(relativePath: String, bytes: ByteArray) =
            error("アルバム画面は画像を書かない")

        override suspend fun read(relativePath: String): ByteArray? = files[relativePath]
        override suspend fun delete(relativePath: String) = error("アルバム画面は画像を消さない")
    }

    private fun stats(sessionCount: Int) = MonthlySnapshotStats(
        distanceMeters = 23_400.0,
        newWayCount = 12,
        discoveredSpeciesNames = listOf("テストカワセミ"),
        sessionCount = sessionCount,
    )

    private fun snapshot(monthIso: String, sessionCount: Int = 9): Snapshot {
        val month = CalendarMonth(monthIso)
        return Snapshot(
            month = month,
            imagePath = month.snapshotImagePath(),
            stats = stats(sessionCount),
            createdAtMs = 1_000L,
        )
    }

    private fun viewModel(
        rows: List<Snapshot>,
        files: Map<String, ByteArray> = emptyMap(),
    ) = AlbumViewModel(
        getSnapshotAlbum = GetSnapshotAlbumUseCase(FakeSnapshotRepository(rows)),
        readSnapshotImage = ReadSnapshotImageUseCase(FakeSnapshotImageStore(files)),
    )

    @Test
    fun `1枚も無ければ空のアルバムになる`() = runTest(dispatcher) {
        val viewModel = viewModel(rows = emptyList())
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isLoading)
        assertEquals(emptyList(), viewModel.uiState.value.months)
    }

    @Test
    fun 月は新しい順に並び数値がそのまま乗る() = runTest(dispatcher) {
        val viewModel = viewModel(
            rows = listOf(
                snapshot("2026-05", sessionCount = 4),
                snapshot("2026-06", sessionCount = 9),
                snapshot("2025-12", sessionCount = 1),
            ),
        )
        advanceUntilIdle()

        val months = viewModel.uiState.value.months
        assertEquals(listOf("2026-06", "2026-05", "2025-12"), months.map { it.month })
        assertEquals(9, months.first().stats.sessionCount)
        assertEquals(listOf("テストカワセミ"), months.first().stats.discoveredSpeciesNames)
    }

    @Test
    fun 画像が無い月も数値のカードとして並ぶ() = runTest(dispatcher) {
        // 手動インポートでDBだけ入った・バックアップの復元が画像に追いついていない状況
        val viewModel = viewModel(rows = listOf(snapshot("2026-06")))
        advanceUntilIdle()

        val month = viewModel.uiState.value.months.single()
        assertNull(month.image, "読めなければ null（例外にしない）")
        assertEquals(9, month.stats.sessionCount, "数値は stats_json 側にあるので出せる")
    }

    @Test
    fun 壊れた画像でもアルバム全体は開く() = runTest(dispatcher) {
        val viewModel = viewModel(
            rows = listOf(snapshot("2026-06"), snapshot("2026-05")),
            // PNGとして解釈できないバイト列
            files = mapOf("snapshots/2026-06.png" to byteArrayOf(1, 2, 3)),
        )
        advanceUntilIdle()

        assertEquals(2, viewModel.uiState.value.months.size, "1枚が壊れても一覧は出る")
        assertTrue(viewModel.uiState.value.months.all { it.image == null })
    }

    @Test
    fun 数値の1行に距離と回数と本数と発見数を出す() {
        // 距離の書式は振り返り（formatDistanceKm）と同じ＝月合計と振り返りの足し算が
        // 同じ見え方になる
        assertEquals(
            "23.4km・散歩 9回・新しい道 12本・発見 1",
            formatMonthSummary(stats(sessionCount = 9)),
        )
    }

    @Test
    fun 月の見出しは日本語の1行にする() {
        assertEquals("2026年6月", formatMonthLabel("2026-06"))
        assertEquals("2026年12月", formatMonthLabel("2026-12"))
        // 想定外の形は素のまま（アルバムが開かなくなるより見慣れない見出しの方がまし）
        assertEquals("こわれた", formatMonthLabel("こわれた"))
    }
}

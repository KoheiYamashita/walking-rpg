package com.walkingrpg.shared.domain.snapshot

import com.walkingrpg.shared.domain.steps.CalendarDay
import com.walkingrpg.shared.domain.steps.CalendarDays
import com.walkingrpg.shared.platform.SnapshotImageStore

/**
 * 月次スナップショットまわりのテスト用差し替え。
 *
 * データ層（SQLDelight）とレンダラ（Compose）はそれぞれの層のテストで見るので、
 * ここでは「UseCaseが何をどの順で読み書きするか」だけを見られる最小の実装にする
 * （`FakeDerivationRepositories` と同じ方針）。
 */

/**
 * `snapshot` のインメモリ版。本実装（`INSERT OR IGNORE`）と同じく、
 * 既にある月は**書き換えない**。
 */
internal class FakeSnapshotRepository(
    rows: List<Snapshot> = emptyList(),
) : SnapshotRepository {

    private val byMonth = rows.associateBy { it.month }.toMutableMap()

    /** 挿入を試みた月（拒否されたぶんも含む）。順序も見たいので列で持つ。 */
    val insertAttempts = mutableListOf<CalendarMonth>()

    override suspend fun insertIfAbsent(snapshot: Snapshot): Boolean {
        insertAttempts += snapshot.month
        if (byMonth.containsKey(snapshot.month)) return false
        byMonth[snapshot.month] = snapshot
        return true
    }

    override suspend fun snapshot(month: CalendarMonth): Snapshot? = byMonth[month]

    override suspend fun snapshots(): List<Snapshot> =
        byMonth.values.sortedByDescending { it.month.iso }

    override suspend fun count(): Int = byMonth.size
}

/** 画像の置き場のインメモリ版。 */
internal class FakeSnapshotImageStore(
    private val failWith: Throwable? = null,
) : SnapshotImageStore {

    private val files = mutableMapOf<String, ByteArray>()

    /** 書き込みが起きたパス（順序を見たいので列で持つ）。 */
    val writtenPaths = mutableListOf<String>()

    val paths: Set<String> get() = files.keys

    override suspend fun write(relativePath: String, bytes: ByteArray) {
        failWith?.let { throw it }
        writtenPaths += relativePath
        files[relativePath] = bytes
    }

    override suspend fun read(relativePath: String): ByteArray? = files[relativePath]

    override suspend fun delete(relativePath: String) {
        files.remove(relativePath)
    }
}

/**
 * 描画の差し替え。中身は見ないので固定のバイト列を返す。
 *
 * 渡された [SnapshotScene] を控えるのは、「散歩が0件の月では呼ばれない」ことと
 * 「画像を書く前に描かれる」ことを確かめるため。
 */
internal class FakeMonthlySnapshotRenderer(
    private val bytes: ByteArray = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47),
    private val failWith: Throwable? = null,
) : MonthlySnapshotRenderer {

    val renderedMonths = mutableListOf<CalendarMonth>()

    override suspend fun render(scene: SnapshotScene, stats: MonthlySnapshotStats): ByteArray {
        failWith?.let { throw it }
        renderedMonths += scene.month
        return bytes
    }
}

/**
 * 暦を「1ヶ月＝1000ms・1日＝100ms」に潰した [CalendarDays]。
 *
 * 本物の暦（うるう年・夏時間）は `SystemCalendarDays` のテストで見るので、
 * UseCaseのテストではむしろ**境界が読める数字**の方がよい：
 * 月 `YYYY-MM` を「西暦0年からの通し月」に直して 1000ms 刻みで並べる。
 *
 * @param nowMs 「今」。`today()` / `monthOf(today())` がここから出る。
 */
internal class SyntheticCalendarDays(
    private val nowMs: Long,
) : CalendarDays {

    override fun today(): CalendarDay = day(nowMs)

    override fun day(epochMillis: Long): CalendarDay {
        val monthIndex = (epochMillis / MONTH_MS).toInt()
        val dayOfMonth = ((epochMillis % MONTH_MS) / DAY_MS).toInt() + 1
        return CalendarDay("${monthIso(monthIndex)}-${dayOfMonth.pad()}")
    }

    override fun monthOf(day: CalendarDay): CalendarMonth = CalendarMonth(day.iso.take(7))

    override fun previousMonth(month: CalendarMonth): CalendarMonth =
        CalendarMonth(monthIso(month.index() - 1))

    override fun monthRange(month: CalendarMonth): MonthRange = MonthRange(
        fromMs = month.index() * MONTH_MS,
        untilMs = (month.index() + 1) * MONTH_MS,
    )

    override fun previousDay(day: CalendarDay): CalendarDay = error("このテストでは使わない")

    override fun daysBetween(from: CalendarDay, until: CalendarDay): Int =
        error("このテストでは使わない")

    /** 西暦0年からの通し月。 */
    private fun CalendarMonth.index(): Int {
        val year = iso.substringBefore('-').toInt()
        val month = iso.substringAfter('-').toInt()
        return year * MONTHS_PER_YEAR + (month - 1)
    }

    private fun monthIso(index: Int): String =
        "${(index / MONTHS_PER_YEAR).toString().padStart(4, '0')}-" +
            "${(index % MONTHS_PER_YEAR + 1).pad()}"

    private fun Int.pad(): String = toString().padStart(2, '0')

    companion object {
        const val MONTH_MS = 1_000L
        const val DAY_MS = 100L
        private const val MONTHS_PER_YEAR = 12

        /** その月の1日目0:00にあたる時刻（テストで「この月の散歩」を書きやすくする）。 */
        fun monthStartMs(iso: String): Long {
            val year = iso.substringBefore('-').toInt()
            val month = iso.substringAfter('-').toInt()
            return (year * MONTHS_PER_YEAR + (month - 1)) * MONTH_MS
        }
    }
}

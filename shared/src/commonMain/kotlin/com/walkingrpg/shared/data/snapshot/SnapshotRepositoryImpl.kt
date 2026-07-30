package com.walkingrpg.shared.data.snapshot

import com.walkingrpg.shared.data.db.WalkingRpgDatabase
import com.walkingrpg.shared.domain.snapshot.CalendarMonth
import com.walkingrpg.shared.domain.snapshot.Snapshot
import com.walkingrpg.shared.domain.snapshot.SnapshotRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [SnapshotRepository] のSQLDelight実装。
 *
 * ドメインモデル⇄DBスキーマの変換はこのクラスに閉じる（architecture.md §2）。
 * `stats_json` の形は [MonthlySnapshotStatsCodec] が持つ。
 */
internal class SnapshotRepositoryImpl(
    private val database: WalkingRpgDatabase,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : SnapshotRepository {

    private val snapshots get() = database.snapshotQueries

    /**
     * 挿入は `INSERT OR IGNORE`（Snapshot.sq）。
     *
     * 「既にあるか」を先に確かめてから入れる形にしないのは、確認と挿入の間に
     * 別の呼び出しが入る隙をSQL側で閉じておくため。入ったかどうかは
     * **件数の差**で見る（`changes()` を引く1本を足すより、この表の小ささなら軽い）。
     * 1トランザクションに包むのは、その差が他の挿入で汚れないようにするため。
     */
    override suspend fun insertIfAbsent(snapshot: Snapshot): Boolean = withContext(dispatcher) {
        snapshots.transactionWithResult {
            val before = snapshots.countSnapshots().executeAsOne()
            snapshots.insertSnapshot(
                month = snapshot.month.iso,
                image_path = snapshot.imagePath,
                stats_json = MonthlySnapshotStatsCodec.encode(snapshot.stats),
                created_at = snapshot.createdAtMs,
            )
            snapshots.countSnapshots().executeAsOne() > before
        }
    }

    override suspend fun snapshot(month: CalendarMonth): Snapshot? = withContext(dispatcher) {
        snapshots.selectSnapshot(month.iso).executeAsOneOrNull()?.toSnapshot()
    }

    override suspend fun snapshots(): List<Snapshot> = withContext(dispatcher) {
        snapshots.selectAllSnapshots().executeAsList().map { it.toSnapshot() }
    }

    override suspend fun count(): Int = withContext(dispatcher) {
        snapshots.countSnapshots().executeAsOne().toInt()
    }
}

/**
 * 行 → ドメインモデル。
 *
 * `month` の形（'YYYY-MM'）と `stats_json` の形が壊れていれば例外になる。
 * 黙って既定値で埋めないのは、この表が作り直せない（`Snapshot.sq`）＝
 * 壊れた行を「空のアルバム」として通すと、壊れたことに気付く機会が無くなるから。
 */
private fun com.walkingrpg.shared.data.db.Snapshot.toSnapshot(): Snapshot = Snapshot(
    month = CalendarMonth(month),
    imagePath = image_path,
    stats = MonthlySnapshotStatsCodec.decode(stats_json),
    createdAtMs = created_at,
)

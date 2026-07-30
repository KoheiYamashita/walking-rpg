package com.walkingrpg.shared.domain.snapshot

/**
 * 月次スナップショットの永続化境界（architecture.md §2「Repository」）。
 *
 * ドメイン層はこのインターフェースだけを知る。SQLDelightのスキーマ変換と
 * `stats_json` のシリアライズはデータ層（`SnapshotRepositoryImpl`）に閉じる。
 *
 * **更新も削除も持たない。** 一度作った月は壊さない（`Snapshot.sq` のコメント）ので、
 * 書き口は「無ければ入れる」1つだけ。他の表（`way_growth` など）にある
 * `replaceAll` 系がここに無いのはそのため。
 */
interface SnapshotRepository {

    /**
     * その月の行がまだ無ければ入れる（`INSERT OR IGNORE`）。
     *
     * @return 入ったら `true`、既にあって何もしなかったら `false`。
     *  生成が冪等であること（同じ月を二度作らない）を呼び出し側が確かめられるように返す。
     */
    suspend fun insertIfAbsent(snapshot: Snapshot): Boolean

    /** その月の1枚（無ければ `null`）。生成済みかどうかの判定に使う。 */
    suspend fun snapshot(month: CalendarMonth): Snapshot?

    /** 全ての1枚を**新しい月から**返す（アルバムの並び）。 */
    suspend fun snapshots(): List<Snapshot>

    /** 保存されている枚数。 */
    suspend fun count(): Int
}

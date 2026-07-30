package com.walkingrpg.shared.platform

/**
 * スナップショット画像の置き場（architecture.md §6「OS自動バックアップ」）。
 *
 * ## 相対パスしか扱わない
 * 引数は永続領域からの**相対パス**（`snapshots/2026-06.png`）で、実際のルートは
 * この向こう側（Android: `filesDir` / iOS: `NSDocumentDirectory`）に閉じる。
 * DBに絶対パスを持たない理由は `Snapshot.sq` のコメントと同じ：サンドボックスのパスは
 * OS更新・機種変更・再インストールで変わるので、行が指す先が黙って壊れる。
 *
 * ## キャッシュ領域には置かない
 * 「街の成長・図鑑・スナップショットは絶対に消さない」（design.md §6）が要件なので、
 * OSがいつでも消せるキャッシュ領域（`cacheDir` / `NSCachesDirectory`）は使わない。
 * 置き場は**OSの自動バックアップの既定対象**（Android Auto Backup /
 * iCloudバックアップ）にそのまま乗る場所を選ぶ。
 *
 * ## 画像の形式は知らない
 * 扱うのは [ByteArray] だけ。PNGにするのはUI層（`MonthlySnapshotRenderer`）で、
 * ここは「消えない場所に置いて、また読める」ことだけを引き受ける。
 */
interface SnapshotImageStore {

    /**
     * [relativePath] に [bytes] を書く（親ディレクトリは必要なら作る）。既にあれば上書き。
     *
     * 上書きを許すのは、書き込みの途中で落ちた壊れたファイルを次の生成が
     * 書き直せるようにするため。**保存済みの月を作り直さない**保証は
     * DBの行の有無で行う（`GenerateMonthlySnapshotsUseCase`）。
     */
    suspend fun write(relativePath: String, bytes: ByteArray)

    /** [relativePath] の中身。無ければ `null`（読めなかったのも `null`）。 */
    suspend fun read(relativePath: String): ByteArray?

    /**
     * [relativePath] を消す。無ければ何もしない。
     *
     * 通常の運用では呼ばない（保存した1枚は消さない）。持っているのは、
     * 手動インポート（issue #18）でアルバムを丸ごと入れ替えるときの後始末のため。
     */
    suspend fun delete(relativePath: String)
}

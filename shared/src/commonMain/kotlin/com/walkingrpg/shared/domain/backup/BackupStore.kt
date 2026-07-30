package com.walkingrpg.shared.domain.backup

/**
 * バックアップ対象の全データを読む／丸ごと入れ替える境界（architecture.md §2「Repository」）。
 *
 * ## なぜテーブルごとのRepositoryを使わないのか
 * インポートは**全削除→挿入を1トランザクション**で行う必要がある
 * （途中で失敗したら「セッションは新しいのに通過は古い」DBが残る＝
 * 真実の源が壊れる）。既存のRepositoryは表ごとに分かれていて、
 * それぞれが自分のトランザクションを開くので、跨いだ原子性を作れない。
 * だからバックアップ専用の境界を1枚立てて、実装側（データ層）が
 * 1つの `database.transaction {}` に全部を入れる。
 *
 * 書き口が既存Repositoryに無いものもここに要る（IDを保った
 * セッション挿入・各表の全削除。`WalkSession.sq` の `insertSessionWithId`）。
 * それらを既存のRepositoryに足さないのは、**通常の記録経路から呼べてしまう**のを
 * 避けるため（真実の源を消す口は、バックアップという文脈の中にだけ置く）。
 */
interface BackupStore {

    /** バックアップに入れる全データ（スナップショット画像も含む）を読む。 */
    suspend fun read(): BackupData

    /**
     * 今あるデータを捨てて [data] の状態にする。
     *
     * 実装の約束：
     * - DBの行は**全削除→挿入を1トランザクション**（途中の状態を残さない）
     * - 画像は**DBの行より先に**書く（行だけあって画像が無い「アルバム割れ」を作らない。
     *   `Snapshot.sq`）。使われなくなった画像は消す
     * - 導出（`way_growth` / `codex_progress`）は触らない。
     *   作り直すのは呼び出し側（[ImportBackupUseCase]）の仕事
     */
    suspend fun replaceAll(data: BackupData)
}

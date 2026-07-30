package com.walkingrpg.shared.domain.backup

import com.walkingrpg.shared.domain.Clock
import com.walkingrpg.shared.platform.BackupArchive
import com.walkingrpg.shared.platform.FileShare

/**
 * 手動エクスポート：全データをzipにまとめてOSの共有UIへ渡す
 * （architecture.md §6「機種変更はエクスポート→インポートで完結」）。
 *
 * ## なぜDBファイルのコピーではなく行ベースのJSONなのか
 * 「`walking_rpg.db` と `snapshots/` をzipに入れるだけ」が最短に見えるが、実際には採れない：
 *
 * - `DatabaseDriverFactory` はDBのパスを持たない（ドライバを作るだけ）し、
 *   DBを閉じる経路もWAL checkpointの口も無い＝**一貫したファイルを取り出せない**
 * - `VACUUM INTO`（整合したコピーを作る手段）はSQLite 3.27+ で、minSdk 26 の
 *   端末内蔵SQLiteでは使えない
 * - インポートでファイルを差し替えると、開いているドライバは古いファイルを掴んだまま。
 *   SQLDelightの購読（Flow）に通知が飛ばないので**アプリの再起動が必要**になる
 *
 * 行ベースのJSONなら `schemaVersion` でスキーマの進化に耐えられ、復元は生成クエリ経由
 * ＝購読が動いてUIが即追従する。既存の `WalkSessionExporterImpl` /
 * `GpsReplayFixture` の形とも繋がる。
 *
 * ## 位置情報の警告
 * **書き出すzipには実際の歩行位置（＝自宅を含む）が入る**（[BackupData] のKDoc）。
 * 出力先はユーザーが選ぶ共有先だけで、リポジトリへ自動で持ち込む経路は作らない
 * （`WalkSessionExporter` と同じ約束。CONTRIBUTING.md「位置情報の扱い」）。
 */
class ExportBackupUseCase(
    private val backupStore: BackupStore,
    private val codec: BackupCodec,
    private val archive: BackupArchive,
    private val fileShare: FileShare,
    private val clock: Clock,
) {
    /**
     * @return 書き出したファイル名と件数（画面に出す）。
     */
    suspend operator fun invoke(): ExportBackupResult {
        val data = backupStore.read()
        val exportedAtMs = clock.nowMillis()
        val zipBytes = archive.write(codec.encode(data, exportedAtMs))

        // ファイル名に時刻を入れるのは、複数世代を並べて保管できるようにするため
        // （インポートは破壊的なので、戻せる先が1つしか無い状態を作らない）。
        val fileName = "walking-rpg-backup-$exportedAtMs.zip"
        fileShare.shareBytes(fileName, MIME_TYPE_ZIP, zipBytes)

        return ExportBackupResult(
            fileName = fileName,
            counts = data.counts,
            byteSize = zipBytes.size,
        )
    }

    private companion object {
        const val MIME_TYPE_ZIP = "application/zip"
    }
}

/** 書き出した結果（画面に出す情報だけ）。 */
data class ExportBackupResult(
    val fileName: String,
    val counts: BackupCounts,
    val byteSize: Int,
)

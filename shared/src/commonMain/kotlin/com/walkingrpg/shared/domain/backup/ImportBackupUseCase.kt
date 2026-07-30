package com.walkingrpg.shared.domain.backup

import com.walkingrpg.shared.domain.codex.RecomputeCodexProgressUseCase
import com.walkingrpg.shared.domain.growth.RecomputeWayGrowthUseCase
import com.walkingrpg.shared.platform.BackupArchive
import com.walkingrpg.shared.platform.FilePicker
import kotlinx.coroutines.CancellationException

/**
 * 手動インポート：zipを選ばせて、その中身でこの端末のデータを**丸ごと置き換える**
 * （architecture.md §6）。
 *
 * ```
 * ファイル選択 → zipをほどく → 検証（ここまでDBに一切書かない）
 *   → 全削除→挿入を1トランザクション（画像 → DBの行の順）
 *   → 導出の再計算（way_growth → codex_progress）
 * ```
 *
 * ## 「何も書かずに失敗する」ことが最重要
 * インポートは破壊的な操作なので、**検証はすべて書き込みの前に終わらせる**
 * （[BackupCodec.decode] が `schemaVersion` ・必須エントリ・件数整合を見て
 * [BackupFormatException] を投げる）。壊れたzipを選んだ結果として
 * 「元のデータは消えたが復元もされていない」を作らない。
 *
 * ## 導出は再計算する。passage はしない
 * `way_growth` / `codex_progress` は捨てて作り直せるキャッシュなので、復元後に
 * 再計算して整合させる（冪等性がここで効く。architecture.md §4「原則」）。
 * 一方 `passage` は zip から**そのまま復元**する：再マッチもできるが、
 * 閾値やアルゴリズムが変わっていると復元後の街が元の端末と違う姿になる。
 * 「同一状態に戻す」ことを優先し、再マッチはマスタ再取り込み時の既存経路に任せる。
 *
 * [com.walkingrpg.shared.domain.growth.RecomputeAfterWalkUseCase] を使わないのは、
 * あちらに `Recent*Repository`（「今回の散歩で育った道／出た種」の強調）への副作用が
 * あるため：インポートは散歩ではないので、余韻を出す相手がいない。
 *
 * ## 前提：対象圏のマスタが取り込み済みであること
 * `codex_progress` の再計算はPOI×wayの空間結合を通るので、**`poi` / `way` マスタに
 * 依存する**（`RecomputeCodexProgressUseCase`）。マスタが空だと図鑑進捗は0件になる
 * （`passage` は復元済みなので、`ReimportOsmAreaUseCase` を流せば埋まる）。
 * 通常はセットアップゲート（`SetupGate`）が取り込み済みを保証しているので、
 * この経路に辿り着く時点でマスタはある。
 */
class ImportBackupUseCase(
    private val filePicker: FilePicker,
    private val archive: BackupArchive,
    private val codec: BackupCodec,
    private val backupStore: BackupStore,
    private val recomputeWayGrowth: RecomputeWayGrowthUseCase,
    private val recomputeCodexProgress: RecomputeCodexProgressUseCase,
) {
    suspend operator fun invoke(): ImportBackupResult {
        // --- ここから下、replaceAll までは1バイトも書かない ---
        val zipBytes = filePicker.pickBytes(MIME_TYPE_ZIP) ?: return ImportBackupResult.Cancelled

        val data = try {
            codec.decode(archive.read(zipBytes))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            // zipとして壊れている（BackupArchive.read）か、中身が期待した形でない
            // （BackupFormatException）。どちらも「選んだファイルの問題」として同じに出す。
            return ImportBackupResult.Failure(
                error.message ?: "バックアップファイルを読めませんでした。",
            )
        }

        // --- 書き込み。ここから先の失敗は例外として上（ViewModel）へ投げる ---
        // 握りつぶすと「半分入った」状態に気付けない。DBの行は1トランザクションなので、
        // 落ちても行は前の状態のまま（画像だけ入れ替わりうる＝行が指す画像は必ず在る）。
        backupStore.replaceAll(data)

        // 導出の作り直し。way_growth と codex_progress の間に依存は無いが、
        // 順序は RecomputeAfterWalkUseCase と揃えておく（読む人が迷わない）。
        recomputeWayGrowth()
        recomputeCodexProgress()

        return ImportBackupResult.Success(counts = data.counts)
    }

    private companion object {
        const val MIME_TYPE_ZIP = "application/zip"
    }
}

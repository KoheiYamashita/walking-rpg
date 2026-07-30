package com.walkingrpg.shared.domain.backup

import com.walkingrpg.shared.platform.BackupEntry

/**
 * [BackupData] ⇄ zipのエントリ列（`manifest.json` ＋ 各表のJSON ＋ 画像）の変換境界。
 *
 * 実装はデータ層（`BackupArchiveCodec`）。`@Serializable` をドメインモデルに付けない
 * ためにこの境界を切っている（architecture.md §3「domain は外部依存なしの純Kotlin」。
 * `WalkSessionExporterImpl` の `SessionExportPayload` /
 * `MonthlySnapshotStatsCodec` と同じ先例）。
 *
 * zipのバイト列そのものを扱うのは1つ外側（`BackupArchive`）で、ここは
 * 「どのエントリに何が入るか」だけを決める。
 *
 * ## 申し送り：シナリオ進行
 * `arc` / `episode` / `chain_step` / `fragment` はまだ実装が無いのでバックアップに
 * 入っていない。**シナリオを実装したら、この codec にエントリを足すこと**
 * （謎の進行は `passage` から再計算できない＝入れ忘れると引き継げない）。
 * 足すときは `schemaVersion` を上げ、[decode] が古いzipを読めるままにしておく
 * （欠けたエントリを「0件」として受ける形）。
 */
interface BackupCodec {

    /**
     * zipに入れるエントリ列を組む。
     *
     * @param exportedAtMs `manifest.json` に書き出す作成時刻（[com.walkingrpg.shared.domain.Clock] 由来）。
     */
    fun encode(data: BackupData, exportedAtMs: Long): List<BackupEntry>

    /**
     * エントリ列を読み解く。
     *
     * **形が期待どおりでなければ [BackupFormatException] を投げる**
     * （`schemaVersion` の不一致・必須エントリの欠落・`manifest.json` の件数と
     * 実際の件数の食い違い・スナップショットの行に対応する画像が無い）。
     * 部分的に読める分だけ返すことはしない：中途半端なデータで全削除が走るほうが
     * 「壊れたファイルでした」より遥かに悪い。
     */
    fun decode(entries: List<BackupEntry>): BackupData
}

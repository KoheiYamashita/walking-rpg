package com.walkingrpg.shared.domain.backup

import com.walkingrpg.shared.domain.llm.LlmCacheEntry
import com.walkingrpg.shared.domain.llm.UtteranceRecord
import com.walkingrpg.shared.domain.matching.Passage
import com.walkingrpg.shared.domain.snapshot.Snapshot
import com.walkingrpg.shared.domain.steps.StepImport
import com.walkingrpg.shared.domain.walk.LocationSample
import com.walkingrpg.shared.domain.walk.WalkSession
import com.walkingrpg.shared.domain.weather.SessionWeather

/**
 * 手動エクスポート／インポートのドメインモデル（architecture.md §6・issue #18）。
 *
 * 純Kotlin。zipもJSONもSQLDelightの行型もここには現れない
 * （zipはプラットフォーム層 `BackupArchive`、JSONの形はデータ層 `BackupCodec` の実装）。
 */

/**
 * 1つのバックアップに入る全データ。
 *
 * ## 何を入れて、何を入れないか
 *
 * **入れる（真実の源とそれに準ずるもの）**
 * - [sessions] / [samples]：真実の源そのもの（architecture.md §4「絶対に消さない」）
 * - [passages]：導出だが再計算コストが高く、真実の源に準ずる扱い。
 *   **再計算せずそのまま復元する**＝閾値やアルゴリズムが変わっていても
 *   「元の端末と同じ街」が戻る（再マッチはマスタ再取り込み時の既存経路に任せる）
 * - [stepImports]：押し忘れ救済の観測値（位置が無いので再現できない）
 * - [sessionWeathers]：外部APIの観測値。過去日の天候は遡れる期間に限りがある＝二度と取れない
 * - [utterances]：再計算できない追記ログ。消すと単調化対策が失われる（`UtteranceLog.sq`）
 * - [snapshots] / [snapshotImages]：耐久コアの筆頭。画像は生成時点の姿で作り直せない
 * - [llmCacheEntries]：**捨てても作り直せる導出キャッシュだが、あえて入れる。**
 *   作り直しはLLMの再課金を意味するので、機種変更で生成文が戻るほうが得。
 *   `prompt_hash` は同じ事実から計算し直されるので、復元後も突き合わせは成立する
 *
 * **入れない**
 * - `way` / `poi`（マスタ）：`ReimportOsmAreaUseCase` で作り直せる。
 *   OSM側が更新されているなら、古い姿を運ぶより取り直すほうが正しい
 * - `way_growth` / `codex_progress`（導出）：インポート後に再計算する
 *   （冪等性がここで効く。architecture.md §4「原則」）
 * - **自宅座標・APIキー**：セキュアストレージ側にあり、そもそもこの型に載らない
 *   （[HOME_NOT_RESTORED_NOTICE]）
 * - シナリオ進行（`arc` / `episode` / `chain_step` / `fragment`）：テーブル自体が未実装。
 *   実装したらこの型と `BackupCodec` にエントリを足すこと（[BackupCodec] のKDoc）
 *
 * ## 位置情報の警告
 * **[samples] には実際の歩行位置（＝自宅を含む）が入る。**
 * 出力先はユーザーが選ぶ共有先だけで、リポジトリへ自動で持ち込む経路は用意しない
 * （`WalkSessionExporter` と同じ約束。CONTRIBUTING.md「位置情報の扱い」）。
 *
 * @param snapshotImages スナップショット画像。キーは [Snapshot.imagePath] と同じ
 *  **永続領域からの相対パス**（`snapshots/2026-06.png`）。絶対パスは持たない。
 */
data class BackupData(
    val sessions: List<WalkSession>,
    val samples: List<LocationSample>,
    val passages: List<Passage>,
    val stepImports: List<StepImport>,
    val sessionWeathers: List<SessionWeather>,
    val utterances: List<UtteranceRecord>,
    val llmCacheEntries: List<LlmCacheEntry>,
    val snapshots: List<Snapshot>,
    val snapshotImages: List<BackupSnapshotImage>,
) {
    /** 表示用の件数（インポートの結果に出す）。画像はDBの行数と同じなので数えない。 */
    val counts: BackupCounts get() = BackupCounts(
        sessionCount = sessions.size,
        sampleCount = samples.size,
        passageCount = passages.size,
        stepImportCount = stepImports.size,
        sessionWeatherCount = sessionWeathers.size,
        utteranceCount = utterances.size,
        llmCacheCount = llmCacheEntries.size,
        snapshotCount = snapshots.size,
    )
}

/**
 * スナップショット画像1枚。
 *
 * @param relativePath 永続領域からの相対パス（`Snapshot.imagePath` と同じ値）。
 *  zip内のエントリ名もこれをそのまま使う＝復元先を計算し直さない。
 */
data class BackupSnapshotImage(
    val relativePath: String,
    val bytes: ByteArray,
) {
    // ByteArray の equals は参照比較なので中身で比べる形に直す（往復テストが一致を見る）
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BackupSnapshotImage) return false
        return relativePath == other.relativePath && bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int = 31 * relativePath.hashCode() + bytes.contentHashCode()

    override fun toString(): String =
        "BackupSnapshotImage(relativePath=$relativePath, size=${bytes.size})"
}

/** バックアップの中身の件数（`manifest.json` に入り、インポート時の整合検証にも使う）。 */
data class BackupCounts(
    val sessionCount: Int,
    val sampleCount: Int,
    val passageCount: Int,
    val stepImportCount: Int,
    val sessionWeatherCount: Int,
    val utteranceCount: Int,
    val llmCacheCount: Int,
    val snapshotCount: Int,
)

/**
 * zipの中身が期待した形でないときに投げる。
 *
 * **これが投げられた時点でDBには一切書いていない**のが約束
 * （検証はすべて復元の前に終わらせる。[ImportBackupUseCase] のKDoc）。
 */
class BackupFormatException(override val message: String) : Exception(message)

/**
 * インポートの結果。
 *
 * 「選ばずに閉じた」を失敗と区別するのは、画面に赤いエラーを出さないため
 * （キャンセルは失敗ではない）。
 */
sealed interface ImportBackupResult {

    /**
     * 復元できた。
     *
     * @param notice ユーザーに必ず伝えること（[HOME_NOT_RESTORED_NOTICE]）。
     */
    data class Success(
        val counts: BackupCounts,
        val notice: String = HOME_NOT_RESTORED_NOTICE,
    ) : ImportBackupResult

    /** ファイルを選ばずに閉じた。何も起きていない。 */
    data object Cancelled : ImportBackupResult

    /** zipが読めない・形が違う・件数が合わない。**DBは無傷**。 */
    data class Failure(val message: String) : ImportBackupResult
}

/**
 * 自宅位置が戻らないことの案内。
 *
 * 自宅座標はセキュアストレージ（Keystore / Keychain）にあり、
 * OS自動バックアップからも手動エクスポートからも外してある（architecture.md §6）。
 * 復元しただけでは自動終了（自宅ジオフェンス）が働かないので、必ず伝える。
 */
const val HOME_NOT_RESTORED_NOTICE: String =
    "自宅位置は復元されません。設定画面から再登録してください。"

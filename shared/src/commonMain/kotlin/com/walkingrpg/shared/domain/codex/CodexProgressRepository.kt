package com.walkingrpg.shared.domain.codex

/**
 * 図鑑の進捗（`codex_progress`）の永続化境界（architecture.md §2「Repository」）。
 *
 * ドメイン層はこのインターフェースだけを知る。SQLDelightのスキーマ変換は
 * データ層（`CodexProgressRepositoryImpl`）に閉じる。
 */
interface CodexProgressRepository {

    /**
     * 進捗を**丸ごと置き換える**（全削除→挿入を1トランザクション）。
     *
     * 種単位の差分更新にしないのは、`way_growth` と同じ理由：これは
     * 「全 `passage` の集計結果」でしかないので、差分にすると
     * 訪問が消えた種（対象圏を狭めた・セッションを削除した・閾値を変えた再計算）の行が
     * 取り残されて、通っていない場所の生き物が図鑑に残る。
     */
    suspend fun replaceAllProgresses(progresses: List<CodexProgress>)

    /** 全ての進捗を種ID順に返す。 */
    suspend fun progresses(): List<CodexProgress>

    /** 1種の進捗。訪問0回なら `null`。 */
    suspend fun progress(speciesId: String): CodexProgress?
}

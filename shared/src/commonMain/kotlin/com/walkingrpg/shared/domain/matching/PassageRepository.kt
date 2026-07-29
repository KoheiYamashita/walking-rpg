package com.walkingrpg.shared.domain.matching

/**
 * 通過（`passage`）の永続化境界（architecture.md §2「Repository」）。
 *
 * ドメイン層はこのインターフェースだけを知る。SQLDelightのスキーマ変換は
 * データ層（`PassageRepositoryImpl`）に閉じる。
 */
interface PassageRepository {

    /**
     * 指定セッションの通過を**丸ごと置き換える**（全削除→挿入を1トランザクション）。
     *
     * 差分更新にしないのは、閾値を厳しくした再計算で「前回は通過だったが今回は違う」
     * 行を確実に消すため。`passage` は `location_sample` から作り直せる（Passage.sq）ので、
     * セッション単位で捨てて入れ直してよい。同じ入力からは必ず同じ状態になる（冪等）。
     */
    suspend fun replaceSessionPassages(sessionId: Long, passages: List<Passage>)

    /** 指定セッションの通過を時刻順に返す。 */
    suspend fun passages(sessionId: Long): List<Passage>

    /**
     * 全セッションの通過を道ごとに数える（way ID → 通過回数）。回数が0の道は現れない。
     *
     * 成長（`way_growth`）の唯一の材料。全通過を返さず数だけ返すのは、
     * 導出の作り直しが年単位の通過を舐めても軽いままであるようにするため。
     */
    suspend fun passCountsByWay(): Map<Long, Int>
}

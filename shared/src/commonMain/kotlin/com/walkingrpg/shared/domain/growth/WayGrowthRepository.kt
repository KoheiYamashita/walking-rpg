package com.walkingrpg.shared.domain.growth

/**
 * 道の成長（`way_growth`）の永続化境界（architecture.md §2「Repository」）。
 *
 * ドメイン層はこのインターフェースだけを知る。SQLDelightのスキーマ変換は
 * データ層（`WayGrowthRepositoryImpl`）に閉じる。
 */
interface WayGrowthRepository {

    /**
     * 成長を**丸ごと置き換える**（全削除→挿入を1トランザクション）。
     *
     * セッション単位ではなく全件なのは、`way_growth` が
     * 「全 `passage` の集計結果」でしかないから（1本の道は複数のセッションで通る）。
     * 差分更新にすると、通過が消えた道（閾値を厳しくした再計算・セッションの削除）の
     * 行が取り残されて、歩いていない道が育ったままになる。
     */
    suspend fun replaceAllGrowths(growths: List<WayGrowth>)

    /** 全ての道の成長をway ID順に返す。 */
    suspend fun growths(): List<WayGrowth>

    /** 1本の道の成長。未踏（通過0回）なら `null`。 */
    suspend fun growth(wayId: Long): WayGrowth?
}

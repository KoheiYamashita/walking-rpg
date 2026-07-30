package com.walkingrpg.shared.domain.osm

import com.walkingrpg.shared.domain.codex.RecomputeCodexProgressUseCase
import com.walkingrpg.shared.domain.growth.RecomputeAfterWalkUseCase
import com.walkingrpg.shared.domain.matching.RecomputePassagesUseCase

/**
 * 対象圏を取り込み直す（issue #20 設定画面「対象圏」セクション）。
 *
 * ```
 * OSMマスタの取り込み（way / poi の置き換え）→ 図鑑進捗（codex_progress）の作り直し
 * ```
 *
 * [ImportOsmAreaUseCase] を単体で呼ばずにこの1本を通すのは、**POIを入れ替えると
 * 図鑑進捗が食い違う**から。`codex_progress` はPOIと道の空間結合から作る導出
 * （[RecomputeCodexProgressUseCase]）なので、マスタだけ入れ替えると
 * 「もう存在しないPOIに紐づく進捗」や「新しく入ったPOIぶんの取りこぼし」が残る。
 * 初回セットアップ（issue #6）で1回目を取り込むときは進捗がまだ無いので
 * [ImportOsmAreaUseCase] 単体でよく、2回目以降＝設定画面からの再取り込みがここの担当。
 *
 * ## `RecomputeAfterWalkUseCase` を流用しない理由
 *
 * あちらは散歩1回ぶんの入口で、`RecentGrowthRepository` / `RecentCodexRepository` への
 * 副作用（「今回育った道」「今回出た種」の強調）を持つ（[RecomputeAfterWalkUseCase] のKDoc）。
 * 設定画面の再取り込みは散歩ではないので、それを叩くと歩いてもいないのに
 * 地図と図鑑の余韻が書き換わる（直前の散歩の強調が消える）。再取り込みで作り直したいのは
 * 図鑑進捗だけなので、副作用の無い1本をここに置く。
 *
 * ## 触らないもの
 *
 * - **`way_growth`**：`passage` の集計なので、マスタを入れ替えても値は変わらない。
 *   消えたwayの行が孤立して残るが、FKは張っていないし、地図はマスタに在るwayしか
 *   描かない（`GetMapSceneUseCase`）ので描画にも出ない。次の散歩終了時の全件作り直しで消える
 * - **`llm_cache`**：キーはOSMのIDなので、同じ地物が取り直されれば再ヒットする。
 *   消えた地物ぶんは参照されないだけ
 * - **`passage`**：全セッションの再マッチはここではやらない（スコープ外）。
 *   取り込み半径やフィルタ（[WALKABLE_HIGHWAY_VALUES] / [PoiSafetyFilter]）を変えて
 *   **道の集合が変わった**ときは、通過そのものを引き直す必要がある。
 *   その口は [RecomputePassagesUseCase]（セッション単位）なので、
 *   全セッションに流す入口が必要になったらそこから組む
 *
 * ## 新POIの文章
 *
 * 取り込みで増えたPOIのフレーバーは**次回のドレインで埋まる**
 * （`PrebatchPoiFlavorUseCase` が「未生成を数え直す」冪等なキューなので、
 * `AppViewModel` が起動時・散歩終了時に流す1本がそのまま拾う）。
 * だから設定画面から明示的に生成を叩かなくてよい。生成できていない地点は
 * 定型文で成立する（design.md §7）ので、埋まるまでの間も破綻しない。
 */
class ReimportOsmAreaUseCase(
    private val importOsmArea: ImportOsmAreaUseCase,
    private val recomputeCodexProgress: RecomputeCodexProgressUseCase,
) {
    /**
     * @return 取り込み1回ぶんの内訳（件数・除外数）。
     *  取り込みが失敗したら図鑑の作り直しには進まない（例外はそのまま外へ）。
     *  古いマスタと古い進捗が組で残るほうが、片方だけ新しい状態より安全なので。
     */
    suspend operator fun invoke(
        radiusMeters: Int = ImportOsmAreaUseCase.DEFAULT_RADIUS_METERS,
    ): OsmImportResult {
        val result = importOsmArea(radiusMeters)
        recomputeCodexProgress()
        return result
    }
}

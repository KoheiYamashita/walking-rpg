package com.walkingrpg.shared.domain.snapshot

import com.walkingrpg.shared.domain.growth.WayGrowthRepository
import com.walkingrpg.shared.domain.map.GrownWayShapes
import com.walkingrpg.shared.domain.osm.OsmMasterRepository

/**
 * 1枚ぶんの描画材料（[SnapshotScene]）を組み立てるUseCase。
 *
 * 材料は地図画面（`GetMapSceneUseCase`）と同じ `way_growth` × `way.geometry` で、
 * 突き合わせの規則は共有の純関数 [GrownWayShapes] に置いてある
 * ＝どの道が落ちるか・どの順で重なるかがアルバムと地図で食い違わない。
 *
 * 地図画面と違うのは3つ：
 * - カメラ（中心＋ズーム）ではなく**全部の道が入る箱**を出す（背景タイルが無いので
 *   縮尺の基準になるものが手元のデータしかない）
 * - 現在地を持たない（永久保存する1枚に「開いた瞬間の居場所」は焼けない）
 * - 直近の散歩の強調を持たない（[SnapshotWay] のKDoc）
 *
 * 対象は**成長済みの全way**で、当月に歩いたぶんだけを切り出したりはしない
 * （[SnapshotScene] のKDoc「その月までに育った街」）。
 * 読むだけで、再計算はしない（再計算の入口は `RecomputeAfterWalkUseCase`）。
 */
class GetMonthlySnapshotSceneUseCase(
    private val wayGrowthRepository: WayGrowthRepository,
    private val osmMasterRepository: OsmMasterRepository,
) {
    suspend operator fun invoke(month: CalendarMonth): SnapshotScene {
        val ways = GrownWayShapes
            .of(growths = wayGrowthRepository.growths(), ways = osmMasterRepository.ways())
            .map { SnapshotWay(shape = it.shape, stage = it.stage) }

        return SnapshotScene(
            month = month,
            bounds = SnapshotProjection.bounds(ways),
            ways = ways,
        )
    }
}

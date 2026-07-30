package com.walkingrpg.shared.domain.map

import com.walkingrpg.shared.domain.growth.RecentGrowthRepository
import com.walkingrpg.shared.domain.growth.WayGrowthRepository
import com.walkingrpg.shared.domain.osm.OsmMasterRepository
import com.walkingrpg.shared.domain.walk.CurrentLocationRepository

/**
 * 地図画面に出すもの一式を組み立てるUseCase。
 *
 * 抽象レイヤー（design.md §8）は `way_growth`（育ちの段階）と `way`（形）の突き合わせ。
 * 成長の行があるのは1回でも通った道だけ（[com.walkingrpg.shared.domain.growth.WayGrowth]）なので、
 * 「歩いた道だけが色を持つ」がそのまま出る＝未踏の道を0段階で並べるコードは要らない。
 *
 * `way_growth` は `passage` から何度でも作り直せる導出キャッシュなので、この画面が
 * 見ているのは常に「歩行ログの累積」そのもの（architecture.md §0）。読むだけで、
 * 再計算はしない（再計算の入口は `RecomputeAfterWalkUseCase`）。
 *
 * カメラの中心は**現在地が取れればそこ、取れなければ広域デフォルト**
 * （[MapCamera.WIDE_DEFAULT]）。ユーザー固有の座標をリポジトリに置かない方針なので、
 * 「対象圏を設定ファイルで指定する」仕組みは持たない。現在地が取れるかどうかは
 * [CurrentLocationRepository] の向こう側（権限・測位）に閉じており、
 * このUseCaseは `null` かどうかしか見ない（権限を促す表示はUI層の担当）。
 */
class GetMapSceneUseCase(
    private val currentLocationRepository: CurrentLocationRepository,
    private val wayGrowthRepository: WayGrowthRepository,
    private val osmMasterRepository: OsmMasterRepository,
    private val recentGrowthRepository: RecentGrowthRepository,
) {
    suspend operator fun invoke(): MapScene {
        val userLocation = currentLocationRepository.currentFix()
            ?.let { GeoPoint(latitude = it.latitude, longitude = it.longitude) }
        val camera = if (userLocation == null) {
            MapCamera.WIDE_DEFAULT
        } else {
            MapCamera(center = userLocation, zoom = FOCUSED_ZOOM)
        }

        return MapScene(
            camera = camera,
            highlights = highlights(),
            userLocation = userLocation,
        )
    }

    /**
     * 育ちのある道だけを、形（マスタの `geometry`）と突き合わせて返す。
     *
     * 突き合わせの規則（マスタに無い道・点が足りない道を落とす／段階の低い順に並べる）は
     * 月次スナップショット（issue #17）と共有する純関数 [GrownWayShapes] に置いてある。
     * ここが足すのは地図画面だけの都合＝「直近の散歩で育ったかどうか」の1つだけ。
     */
    private suspend fun highlights(): List<WayHighlight> {
        val stageRaisedWayIds = recentGrowthRepository.stageRaisedWayIds

        return GrownWayShapes
            .of(growths = wayGrowthRepository.growths(), ways = osmMasterRepository.ways())
            .map { grown ->
                WayHighlight(
                    wayId = grown.wayId,
                    shape = grown.shape,
                    stage = grown.stage,
                    isNewlyGrown = grown.wayId in stageRaisedWayIds,
                )
            }
    }

    private companion object {
        /** 現在地が取れたときのズーム（散歩の縮尺＝街区が見える程度）。 */
        const val FOCUSED_ZOOM = 15.0
    }
}

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
     * マスタに無いway ID（対象圏を取り直してOSM側から消えた道など）は黙って落とす。
     * 形が引けないものは描きようがなく、`passage` 側は真実として残っているので、
     * 次にマスタを取り直せば戻る＝ここで落とすのは表示だけ。
     *
     * 段階の低い順に並べるのは、地図SDKが後ろの要素を上に描くから。
     * 交差点で重なったとき、育っている道の色が下に隠れないようにする。
     */
    private suspend fun highlights(): List<WayHighlight> {
        val growths = wayGrowthRepository.growths()
        if (growths.isEmpty()) return emptyList()

        val shapeByWayId = osmMasterRepository.ways().associate { it.id to it.geometry }
        val stageRaisedWayIds = recentGrowthRepository.stageRaisedWayIds

        return growths
            .mapNotNull { growth ->
                val shape = shapeByWayId[growth.wayId] ?: return@mapNotNull null
                // 1点しかないwayは線にならない（マスタが壊れているとき）ので捨てる
                if (shape.size < MIN_SHAPE_POINTS) return@mapNotNull null
                WayHighlight(
                    wayId = growth.wayId,
                    shape = shape,
                    stage = growth.stage,
                    isNewlyGrown = growth.wayId in stageRaisedWayIds,
                )
            }
            .sortedBy { it.stage }
    }

    private companion object {
        /** 現在地が取れたときのズーム（散歩の縮尺＝街区が見える程度）。 */
        const val FOCUSED_ZOOM = 15.0

        const val MIN_SHAPE_POINTS = 2
    }
}

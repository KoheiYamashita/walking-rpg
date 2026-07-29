package com.walkingrpg.shared.domain

import com.walkingrpg.shared.domain.growth.RecentGrowthRepository
import com.walkingrpg.shared.domain.growth.WayGrowth
import com.walkingrpg.shared.domain.growth.WayGrowthRepository
import com.walkingrpg.shared.domain.map.GeoPoint
import com.walkingrpg.shared.domain.matching.Passage
import com.walkingrpg.shared.domain.matching.PassageRepository
import com.walkingrpg.shared.domain.osm.OsmMasterCounts
import com.walkingrpg.shared.domain.osm.OsmMasterRepository
import com.walkingrpg.shared.domain.osm.Poi
import com.walkingrpg.shared.domain.osm.Way
import com.walkingrpg.shared.domain.walk.CurrentLocationRepository
import com.walkingrpg.shared.domain.walk.LocationFix
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 導出（passage → way_growth → 地図）まわりのテスト用リポジトリ。
 *
 * データ層の実装（SQLDelight）はAndroid実機テスト側で見ているので、
 * ここでは「UseCaseが何をどの順で読み書きするか」だけを見られる最小の差し替えにする。
 */

internal class FakeCurrentLocationRepository(
    private val fix: LocationFix? = null,
) : CurrentLocationRepository {
    override suspend fun currentFix(): LocationFix? = fix
}

internal class FakeOsmMasterRepository(
    private var ways: List<Way> = emptyList(),
) : OsmMasterRepository {

    override suspend fun save(ways: List<Way>, pois: List<Poi>) {
        this.ways = ways
    }

    override suspend fun counts(): OsmMasterCounts =
        OsmMasterCounts(wayCount = ways.size, poiCount = 0)

    override suspend fun ways(): List<Way> = ways

    override suspend fun pois(): List<Poi> = emptyList()
}

internal class FakeWayGrowthRepository(
    growths: List<WayGrowth> = emptyList(),
) : WayGrowthRepository {

    var growths: List<WayGrowth> = growths
        private set

    /** `replaceAllGrowths` が呼ばれた回数（全件の作り直しが1回だけ走ることの確認用）。 */
    var replaceCount = 0
        private set

    override suspend fun replaceAllGrowths(growths: List<WayGrowth>) {
        this.growths = growths
        replaceCount++
    }

    override suspend fun growths(): List<WayGrowth> = growths.sortedBy { it.wayId }

    override suspend fun growth(wayId: Long): WayGrowth? = growths.firstOrNull { it.wayId == wayId }
}

/**
 * 通過の差し替え。[passCounts] を直接置けるので、map matching を通さずに
 * 「この道を何回通った状態」を作れる。
 */
internal class FakePassageRepository(
    var passCounts: Map<Long, Int> = emptyMap(),
) : PassageRepository {

    val replacedSessions = mutableListOf<Long>()

    override suspend fun replaceSessionPassages(sessionId: Long, passages: List<Passage>) {
        replacedSessions += sessionId
    }

    override suspend fun passages(sessionId: Long): List<Passage> = emptyList()

    override suspend fun passCountsByWay(): Map<Long, Int> = passCounts
}

internal class FakeRecentGrowthRepository(
    initial: Set<Long> = emptySet(),
) : RecentGrowthRepository {

    private val _stageRaisedWayIds = MutableStateFlow(initial)
    override val stageRaisedWayIds: StateFlow<Set<Long>> = _stageRaisedWayIds.asStateFlow()

    /** `record` に渡された履歴（0件でも記録されることを見たいので列で持つ）。 */
    val recorded = mutableListOf<Set<Long>>()

    override fun record(wayIds: Set<Long>) {
        recorded += wayIds
        _stageRaisedWayIds.value = wayIds
    }
}

/** 2点だけの直線way（形の中身はテストの関心ではないので固定）。 */
internal fun testWay(id: Long, points: List<GeoPoint> = STRAIGHT_SHAPE): Way = Way(
    id = id,
    name = null,
    highway = "residential",
    geometry = points,
    lengthMeters = 100.0,
)

internal val STRAIGHT_SHAPE = listOf(
    GeoPoint(latitude = 35.0, longitude = 139.0),
    GeoPoint(latitude = 35.001, longitude = 139.0),
)

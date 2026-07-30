package com.walkingrpg.shared.domain.feedback

import com.walkingrpg.shared.domain.growth.GrowthStage
import com.walkingrpg.shared.domain.matching.MapMatcher
import com.walkingrpg.shared.domain.matching.SyntheticWalk
import com.walkingrpg.shared.domain.testWay
import com.walkingrpg.shared.domain.walk.LocationSample
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 歩行中の見込み判定（issue #12）。
 *
 * 純関数なので、実測位もDBも要らずにサンプル列だけで確かめられる
 * （architecture.md §7「ドメイン層は純Kotlinなので通常のunit testで全部書ける」）。
 * 道は `testWay` の既定形状＝原点(35.0, 139.0)から真北へ111mの直線1本。
 */
class LiveGrowthEstimatorTest {

    /** 原点から真北に [meters] 離れた点のサンプル。 */
    private fun sample(ts: Long, meters: Double, accuracy: Double = 5.0) = LocationSample(
        sessionId = SESSION_ID,
        timestampMs = ts,
        latitude = ORIGIN_LATITUDE + meters / METERS_PER_DEGREE_LATITUDE,
        longitude = ORIGIN_LONGITUDE,
        accuracyMeters = accuracy,
    )

    private fun estimator(passCounts: Map<Long, Int> = emptyMap()) = LiveGrowthEstimator(
        sessionId = SESSION_ID,
        ways = listOf(testWay(id = WAY_ID)),
        passCounts = passCounts,
    )

    /** サンプル列を全部畳んで、出たイベントを順に返す。 */
    private fun LiveGrowthEstimator.eventsFrom(samples: List<LocationSample>): List<WalkEvent> {
        var current = this
        val events = mutableListOf<WalkEvent>()
        samples.forEach { sample ->
            val update = current.sampleRecorded(sample)
            current = update.estimator
            update.event?.let { events += it }
        }
        return events
    }

    /** 5秒ごとに5m進む（1 m/s＝ゆっくりめの歩行）。 */
    private fun walkAlongWay(count: Int, fromMs: Long = 0L, fromMeters: Double = 0.0) =
        List(count) { index ->
            sample(ts = fromMs + index * 5_000L, meters = fromMeters + index * 5.0)
        }

    @Test
    fun 未踏の道は初回の通過で草に上がる() {
        val events = estimator().eventsFrom(walkAlongWay(count = 2))

        assertEquals(1, events.size, "イベントは1件")
        val growthStageUp = events.single() as WalkEvent.GrowthStageUp
        assertEquals(WAY_ID, growthStageUp.wayId)
        assertEquals(GrowthStage.GRASS, growthStageUp.stage)
        assertEquals(SESSION_ID, growthStageUp.sessionId)
        // 時刻は端末時計ではなく、成立したサンプルの時刻
        assertEquals(5_000L, growthStageUp.timestampMs)
    }

    @Test
    fun 一件しかスナップしていない道は通過と数えない() {
        val events = estimator().eventsFrom(walkAlongWay(count = 1))

        assertTrue(events.isEmpty())
    }

    @Test
    fun 同じ道を歩き続けても通過は1回だけ() {
        val events = estimator().eventsFrom(walkAlongWay(count = 10))

        assertEquals(1, events.size)
    }

    @Test
    fun 段階が変わらない通過ではイベントが出ない() {
        // すでに1回通っている（草）。2回目では花（3回）に届かない
        val events = estimator(passCounts = mapOf(WAY_ID to 1)).eventsFrom(walkAlongWay(count = 4))

        assertTrue(events.isEmpty())
    }

    @Test
    fun 閾値をまたぐ通過で次の段階に上がる() {
        // すでに2回通っている。この散歩の1回で花（3回）に届く
        val events = estimator(passCounts = mapOf(WAY_ID to 2)).eventsFrom(walkAlongWay(count = 4))

        assertEquals(GrowthStage.FLOWER, (events.single() as WalkEvent.GrowthStageUp).stage)
    }

    @Test
    fun 精度の悪いサンプルは判定に使わない() {
        val samples = walkAlongWay(count = 5).map { it.copy(accuracyMeters = 50.0) }

        assertTrue(estimator().eventsFrom(samples).isEmpty())
    }

    @Test
    fun 道から離れたサンプルはどの道にも乗せない() {
        // wayは原点から111mまで。1km先は最寄りwayまで20mを大きく超える
        val samples = walkAlongWay(count = 5, fromMeters = 1_000.0)

        assertTrue(estimator().eventsFrom(samples).isEmpty())
    }

    @Test
    fun 歩行ではありえない速度で飛んだサンプルは無視する() {
        // 1秒で100m＝乗り物（あるいはGPSの飛び）。判定に使わないので塊が成立しない
        val samples = listOf(sample(ts = 0L, meters = 0.0), sample(ts = 1_000L, meters = 100.0))

        assertTrue(estimator().eventsFrom(samples).isEmpty())
    }

    @Test
    fun 測位が長く途切れたら同じ道でも次の通過になる() {
        // 既定の maxPassageGapMs は60秒。往復して戻ってきた形（間に別の道は無い）
        val samples = walkAlongWay(count = 2) +
            walkAlongWay(count = 2, fromMs = 600_000L)

        // 未踏 → 1回目で草、2回目は花（3回）に届かないので静か
        assertEquals(1, estimator().eventsFrom(samples).size)
        // 2回ぶん数えていることは、閾値の手前から始めれば分かる
        val fromGrass = estimator(passCounts = mapOf(WAY_ID to 1)).eventsFrom(samples)
        assertEquals(GrowthStage.FLOWER, (fromGrass.single() as WalkEvent.GrowthStageUp).stage)
    }

    @Test
    fun 交差点で横断する道を挟んでも見込みは確定と同じ回数になる() {
        // MapMatcherTest「交差点で横断する道を数秒挟んでも元の道は1通過」と同じ形。
        // 見込みが確定より多く鳴ると、振り返りで「育っていない」と食い違う。
        val mainStreet = SyntheticWalk.eastWestWay(id = 1L, northMeters = 0.0, fromEast = 0.0, toEast = 300.0)
        val crossing = SyntheticWalk.northSouthWay(
            id = 6L,
            eastMeters = 150.0,
            fromNorth = -100.0,
            toNorth = 100.0,
        )
        val points = List(18) { SyntheticWalk.point(0.0, 100.0 + it * 2.8) } +
            List(3) { SyntheticWalk.point(8.0, 148.0 + it * 2.0) } +
            List(18) { SyntheticWalk.point(0.0, 154.0 + it * 2.8) }
        val walk = SyntheticWalk.samples(points).map { it.copy(sessionId = SESSION_ID) }
        val ways = listOf(mainStreet, crossing)

        var estimator = LiveGrowthEstimator(sessionId = SESSION_ID, ways = ways, passCounts = emptyMap())
        val events = mutableListOf<WalkEvent.GrowthStageUp>()
        walk.forEach { sample ->
            val update = estimator.sampleRecorded(sample)
            estimator = update.estimator
            update.event?.let { events += it }
        }

        val confirmed = MapMatcher.match(SESSION_ID, walk, ways).groupingBy { it.wayId }.eachCount()
        assertEquals(mapOf(1L to 1, 6L to 1), confirmed, "確定：本通り1回・横断した道1回")
        assertEquals(
            confirmed.keys,
            events.map { it.wayId }.toSet(),
            "見込みも同じ道を同じ回数だけ（本通りは戻ってきても数え直さない）",
        )
        assertEquals(2, events.size)
    }

    private companion object {
        const val SESSION_ID = 7L
        const val WAY_ID = 1L

        const val ORIGIN_LATITUDE = 35.0
        const val ORIGIN_LONGITUDE = 139.0

        /** 子午線上の1度の長さ（m）。真北にずらすので経度のスケールを考えなくてよい。 */
        const val METERS_PER_DEGREE_LATITUDE = 111_194.9
    }
}

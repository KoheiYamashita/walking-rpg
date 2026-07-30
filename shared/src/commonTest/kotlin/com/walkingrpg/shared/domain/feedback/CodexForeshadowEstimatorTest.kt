package com.walkingrpg.shared.domain.feedback

import com.walkingrpg.shared.domain.codex.CodexCategory
import com.walkingrpg.shared.domain.codex.CodexConfig
import com.walkingrpg.shared.domain.codex.Species
import com.walkingrpg.shared.domain.matching.SyntheticWalk
import com.walkingrpg.shared.domain.osm.Poi
import com.walkingrpg.shared.domain.osm.PoiKind
import com.walkingrpg.shared.domain.walk.LocationSample
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 歩行中の図鑑判定（[CodexForeshadowEstimator]）。
 *
 * 見たいのは：
 * - 同じPOIは1回だけ数える（往復しても2回にならない）
 * - 閾値ちょうどのときだけ鳴る（既に出た種は再発火しない）
 * - 予兆と出現の両方が出せる
 * - 遠くを通っただけでは何も起きない
 *
 * 確定側（`RecomputeCodexProgressUseCase`）とずれても壊れるものはない（見込みなので）が、
 * 数え方は揃えてある＝ここで見た挙動は帰宅後の結果とだいたい一致する。
 */
class CodexForeshadowEstimatorTest {

    /** 水辺の棚に「3回で出る種」と「10回で出る種」。 */
    private val skimmer = Species(
        id = "skimmer",
        name = "テストトンボ",
        category = CodexCategory.WATER,
        requiredVisitCount = 3,
        foreshadowText = "水面の上を何かが行き来している。",
    )
    private val kingfisher = Species(
        id = "kingfisher",
        name = "テストカワセミ",
        category = CodexCategory.WATER,
        requiredVisitCount = 10,
        foreshadowText = "青い羽根が落ちている。",
    )
    private val species = listOf(skimmer, kingfisher)

    private val river = poi("node/1", PoiKind.WATER, northMeters = 0.0, eastMeters = 0.0)

    /** 河川POIから200m東（既定半径40mの外）にある公園。 */
    private val park = poi("node/2", PoiKind.PARK, northMeters = 0.0, eastMeters = 200.0)

    private fun poi(id: String, kind: PoiKind, northMeters: Double, eastMeters: Double) = Poi(
        id = id,
        kind = kind,
        tags = emptyMap(),
        location = SyntheticWalk.point(northMeters = northMeters, eastMeters = eastMeters),
    )

    /** 原点から北[northMeters]・東[eastMeters]の測位サンプル。 */
    private fun sample(
        index: Int,
        northMeters: Double = 0.0,
        eastMeters: Double = 0.0,
        accuracyMeters: Double = SyntheticWalk.GOOD_ACCURACY_M,
    ): LocationSample {
        val point = SyntheticWalk.point(northMeters = northMeters, eastMeters = eastMeters)
        return LocationSample(
            sessionId = SESSION_ID,
            timestampMs = SyntheticWalk.START_MS + index * SyntheticWalk.INTERVAL_MS,
            latitude = point.latitude,
            longitude = point.longitude,
            accuracyMeters = accuracyMeters,
        )
    }

    private fun estimator(
        visitCountsByPoi: Map<String, Int>,
        pois: List<Poi> = listOf(river, park),
        config: CodexConfig = CodexConfig.DEFAULT,
    ) = CodexForeshadowEstimator(
        sessionId = SESSION_ID,
        pois = pois,
        visitCountsByPoi = visitCountsByPoi,
        species = species,
        config = config,
    )

    /** サンプル列を畳んで、出たイベントを全部集める。 */
    private fun CodexForeshadowEstimator.fold(
        samples: List<LocationSample>,
    ): List<WalkEvent> {
        var current = this
        val events = mutableListOf<WalkEvent>()
        samples.forEach { sample ->
            val update = current.sampleRecorded(sample)
            current = update.estimator
            update.event?.let { events += it }
        }
        return events
    }

    @Test
    fun 閾値ちょうどで出現イベントが出る() {
        // 2回通ってある水辺（3回で出る種）へ3回目の訪問
        val events = estimator(mapOf(river.id to 2, park.id to 0))
            .fold(listOf(sample(0, northMeters = 5.0)))

        val discovered = events.single() as WalkEvent.CodexDiscovered
        assertEquals(skimmer.id, discovered.speciesId)
        assertEquals(river.id, discovered.poiId)
        assertEquals(SESSION_ID, discovered.sessionId)
        assertEquals(SyntheticWalk.START_MS, discovered.timestampMs)
    }

    @Test
    fun 予兆の閾値ちょうどで予兆イベントが出る() {
        // 10回で出る種の2回手前＝8回目の訪問
        val events = estimator(mapOf(river.id to 7, park.id to 0))
            .fold(listOf(sample(0, northMeters = 5.0)))

        val foreshadow = events.single() as WalkEvent.CodexForeshadow
        assertEquals(kingfisher.id, foreshadow.speciesId)
        assertEquals(river.id, foreshadow.poiId)
    }

    @Test
    fun 同じPOIは1回だけ数える() {
        // 同じ川沿いを往復した散歩。3回目の訪問で1回鳴るだけ
        val events = estimator(mapOf(river.id to 2, park.id to 0)).fold(
            listOf(
                sample(0, northMeters = 5.0),
                sample(1, northMeters = 10.0),
                sample(2, northMeters = 200.0),
                sample(3, northMeters = 5.0),
            ),
        )

        assertEquals(1, events.size, "往復しても訪問は1回")
    }

    @Test
    fun 既に出た種は再発火しない() {
        // すでに5回通っている（3回で出る種はもう出ている）
        val events = estimator(mapOf(river.id to 5, park.id to 0))
            .fold(listOf(sample(0, northMeters = 5.0)))

        assertTrue(events.isEmpty(), "6回目の訪問では何も起きない")
    }

    @Test
    fun 閾値でも予兆でもない回は何も出さない() {
        // 4回目の訪問（3回の種は出済み、10回の種はまだ遠い）
        val events = estimator(mapOf(river.id to 3, park.id to 0))
            .fold(listOf(sample(0, northMeters = 5.0)))

        assertTrue(events.isEmpty())
    }

    @Test
    fun 近接半径の外を通っても何も起きない() {
        // 川から100m北（既定半径40mの外）
        val events = estimator(mapOf(river.id to 2, park.id to 0))
            .fold(listOf(sample(0, northMeters = 100.0)))

        assertTrue(events.isEmpty())
    }

    @Test
    fun 精度の悪いサンプルは判定に使わない() {
        val update = estimator(mapOf(river.id to 2, park.id to 0))
            .sampleRecorded(sample(0, northMeters = 5.0, accuracyMeters = 60.0))

        assertNull(update.event)
        // 訪問も立てていないので、次の良いサンプルでちゃんと鳴る
        assertTrue(update.estimator.sampleRecorded(sample(1, northMeters = 5.0)).event != null)
    }

    @Test
    fun 棚の回数はPOI単位の最大値なので近所を回っても進まない() {
        // 同じ棚に2つのPOI。よく通っている方（5回）が既に3回の種を出しているので、
        // 初めて通るもう一方のPOIでは何も起きない
        val otherRiver = poi("node/3", PoiKind.WATER, northMeters = 0.0, eastMeters = 500.0)
        val events = estimator(
            visitCountsByPoi = mapOf(river.id to 5, otherRiver.id to 0),
            pois = listOf(river, otherRiver),
        ).fold(listOf(sample(0, eastMeters = 500.0)))

        assertTrue(events.isEmpty())
    }

    @Test
    fun ひとつのサンプルから出るイベントは1件まで() {
        // 交差点で2つの棚のPOIが同時に近傍に入る状況（design.md §3 沈黙のデザイン）
        val nearbyPark = poi("node/4", PoiKind.PARK, northMeters = 10.0, eastMeters = 0.0)
        val parkSpecies = Species(
            id = "park_one",
            name = "テスト公園種",
            category = CodexCategory.PARK,
            requiredVisitCount = 3,
            foreshadowText = "地面をつつく音がする。",
        )
        val estimator = CodexForeshadowEstimator(
            sessionId = SESSION_ID,
            pois = listOf(river, nearbyPark),
            visitCountsByPoi = mapOf(river.id to 2, nearbyPark.id to 2),
            species = species + parkSpecies,
        )

        val update = estimator.sampleRecorded(sample(0, northMeters = 5.0))

        assertTrue(update.event != null, "1件は出る")
        // 取りこぼした側も訪問は立っているので、同じ散歩で二度数えない
        assertNull(update.estimator.sampleRecorded(sample(1, northMeters = 5.0)).event)
    }

    @Test
    fun 予兆の先行ぶんが閾値を超える設定でも予兆は出さない() {
        // 閾値3回の種に先行5回を設定すると閾値-先行が負になる＝行く前から匂う
        val events = estimator(
            visitCountsByPoi = mapOf(river.id to 0, park.id to 0),
            config = CodexConfig(foreshadowLeadVisits = 5),
        ).fold(listOf(sample(0, northMeters = 5.0)))

        assertTrue(events.isEmpty(), "1回目の訪問で予兆は出ない")
    }

    @Test
    fun 出現と予兆が同時に当たれば出現を優先する() {
        // 5回で出る種と、7回で出る種（先行2回＝5回目が予兆）が同じ棚にある
        val early = skimmer.copy(id = "early", requiredVisitCount = 5)
        val late = kingfisher.copy(id = "late", requiredVisitCount = 7)
        val estimator = CodexForeshadowEstimator(
            sessionId = SESSION_ID,
            pois = listOf(river),
            visitCountsByPoi = mapOf(river.id to 4),
            species = listOf(early, late),
        )

        val event = estimator.sampleRecorded(sample(0, northMeters = 5.0)).event

        assertTrue(event is WalkEvent.CodexDiscovered, "嬉しさの大きい方を落とさない")
        assertEquals("early", (event as WalkEvent.CodexDiscovered).speciesId)
    }

    @Test
    fun POIが無ければ何も起きない() {
        val events = estimator(visitCountsByPoi = emptyMap(), pois = emptyList())
            .fold(listOf(sample(0), sample(1)))

        assertTrue(events.isEmpty())
    }

    private companion object {
        const val SESSION_ID = 7L
    }
}

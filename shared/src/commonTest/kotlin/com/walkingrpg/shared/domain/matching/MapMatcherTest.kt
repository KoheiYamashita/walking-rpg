package com.walkingrpg.shared.domain.matching

import com.walkingrpg.shared.domain.matching.SyntheticWalk.INTERVAL_MS
import com.walkingrpg.shared.domain.matching.SyntheticWalk.SESSION_ID
import com.walkingrpg.shared.domain.matching.SyntheticWalk.START_MS
import com.walkingrpg.shared.domain.matching.SyntheticWalk.eastWestWay
import com.walkingrpg.shared.domain.matching.SyntheticWalk.northSouthWay
import com.walkingrpg.shared.domain.matching.SyntheticWalk.point
import com.walkingrpg.shared.domain.matching.SyntheticWalk.samples
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [MapMatcher] の合成データテスト。
 *
 * 座標はすべて架空（[SyntheticWalk]）。見たいのは
 * 「実際に歩いた道だけが、歩いた回数ぶんだけ通過になる」こと。
 */
class MapMatcherTest {

    // 東西の道（原点から東へ300m）。以下のテストの主役。
    private val mainStreet = eastWestWay(id = 1L, northMeters = 0.0, fromEast = 0.0, toEast = 300.0)

    // 東端で main street に接続する南北の道（交差点で曲がるケース）
    private val crossStreet = northSouthWay(id = 2L, eastMeters = 300.0, fromNorth = 0.0, toNorth = 300.0)

    // main street の12m北を並走する裏道（GPS誤差で誤塗りしやすい相手）
    private val backStreet = eastWestWay(id = 3L, northMeters = 12.0, fromEast = 0.0, toEast = 300.0)

    // 遠くを通る長い道（電車に乗ったときにスナップしてしまう相手）
    private val railsideRoad = eastWestWay(id = 4L, northMeters = 2_000.0, fromEast = 0.0, toEast = 3_000.0)

    /** 歩行速度1.4m/s・2秒間隔＝1サンプル2.8m。 */
    private fun walkEast(fromEast: Double, count: Int, northMeters: Double = 0.0) =
        List(count) { index -> point(northMeters, fromEast + index * 2.8) }

    private fun walkNorth(fromNorth: Double, count: Int, eastMeters: Double) =
        List(count) { index -> point(fromNorth + index * 2.8, eastMeters) }

    @Test
    fun 直線の道を歩くと通過は1本になる() {
        val result = MapMatcher.match(
            sessionId = SESSION_ID,
            samples = samples(walkEast(fromEast = 20.0, count = 40)),
            ways = listOf(mainStreet),
        )

        assertEquals(listOf(Passage(SESSION_ID, wayId = 1L, timestampMs = START_MS)), result)
    }

    @Test
    fun 交差点で曲がると通過は2本になる() {
        val alongMain = walkEast(fromEast = 100.0, count = 30)
        val alongCross = walkNorth(fromNorth = 8.0, count = 30, eastMeters = 300.0)

        val result = MapMatcher.match(
            sessionId = SESSION_ID,
            samples = samples(alongMain + alongCross),
            ways = listOf(mainStreet, crossStreet),
        )

        assertEquals(listOf(1L, 2L), result.map { it.wayId })
        assertEquals(START_MS, result.first().timestampMs)
        // 曲がった先の代表時刻は「その通過で最初にスナップしたサンプルの時刻」
        assertEquals(START_MS + alongMain.size * INTERVAL_MS, result[1].timestampMs)
    }

    @Test
    fun 精度の悪いサンプルは通過を作らない() {
        // 裏道の上に3件だけ座っているが、どれも精度60m＝閾値25m超
        val onMain = samples(walkEast(fromEast = 20.0, count = 20))
        val noisyOnBackStreet = samples(
            points = walkEast(fromEast = 76.0, count = 3, northMeters = 12.0),
            startMs = START_MS + 20 * INTERVAL_MS,
            accuracyMeters = 60.0,
        )
        val backOnMain = samples(
            points = walkEast(fromEast = 84.0, count = 20),
            startMs = START_MS + 23 * INTERVAL_MS,
        )

        val result = MapMatcher.match(
            sessionId = SESSION_ID,
            samples = onMain + noisyOnBackStreet + backOnMain,
            ways = listOf(mainStreet, backStreet),
        )

        assertEquals(listOf(Passage(SESSION_ID, wayId = 1L, timestampMs = START_MS)), result)
    }

    @Test
    fun 全サンプルの精度が悪ければ通過は作られない() {
        val result = MapMatcher.match(
            sessionId = SESSION_ID,
            samples = samples(walkEast(fromEast = 20.0, count = 40), accuracyMeters = 40.0),
            ways = listOf(mainStreet),
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun 電車区間は通過にならない() {
        // 20サンプル歩いたあと、40秒間ずっと20m/sで移動（＝電車）
        val walk = samples(walkEast(fromEast = 20.0, count = 20))
        val train = samples(
            points = List(20) { index -> point(2_000.0, 100.0 + index * 40.0) },
            startMs = START_MS + 20 * INTERVAL_MS,
        )
        val ways = listOf(mainStreet, railsideRoad)

        val result = MapMatcher.match(SESSION_ID, walk + train, ways)

        assertEquals(listOf(1L), result.map { it.wayId })

        // 速度フィルタを実質無効にすると線路沿いの道が塗られる＝落としているのはこのフィルタ
        val withoutSpeedFilter = MapMatcher.match(
            sessionId = SESSION_ID,
            samples = walk + train,
            ways = ways,
            config = MapMatchingConfig(maxWalkingSpeedMps = 1_000.0),
        )
        assertEquals(listOf(1L, 4L), withoutSpeedFilter.map { it.wayId })
    }

    @Test
    fun 一瞬の測位飛びでは歩行区間を落とさない() {
        // 途中の1区間だけ100m飛ぶ（＝50m/s）。乗り物なら数十秒続くはずで、続かない。
        val jumped = samples(walkEast(fromEast = 20.0, count = 20)) +
            samples(
                points = walkEast(fromEast = 176.0, count = 20),
                startMs = START_MS + 20 * INTERVAL_MS,
            )

        val result = MapMatcher.match(SESSION_ID, jumped, listOf(mainStreet))

        assertEquals(listOf(Passage(SESSION_ID, wayId = 1L, timestampMs = START_MS)), result)
    }

    @Test
    fun GPS誤差でジグザグしても並走する裏道は塗らない() {
        // ±3mの揺れ（裏道までは12m）に、裏道寄りへ11m飛ぶ単発の誤りを2回混ぜる
        val northOffsets = listOf(0.0, 2.0, -3.0, 1.0, 11.0, -2.0, 3.0, 0.0, -1.0, 11.0, 2.0, -2.0, 1.0, 0.0)
        val points = northOffsets.mapIndexed { index, north -> point(north, 40.0 + index * 2.8) }

        val result = MapMatcher.match(SESSION_ID, samples(points), listOf(mainStreet, backStreet))

        assertEquals(listOf(Passage(SESSION_ID, wayId = 1L, timestampMs = START_MS)), result)
    }

    @Test
    fun 単発の飛びを均さなければ通過が水増しされる() {
        val northOffsets = listOf(0.0, 2.0, -3.0, 1.0, 11.0, -2.0, 3.0, 0.0)
        val points = northOffsets.mapIndexed { index, north -> point(north, 40.0 + index * 2.8) }

        // minRunSamples = 1 ＝ ノイズ除去なし。1件の飛びが通過3本に化ける
        val result = MapMatcher.match(
            sessionId = SESSION_ID,
            samples = samples(points),
            ways = listOf(mainStreet, backStreet),
            config = MapMatchingConfig(minRunSamples = 1),
        )

        assertEquals(listOf(1L, 3L, 1L), result.map { it.wayId })
    }

    @Test
    fun 同じ道に戻ってくると別の通過になる() {
        val out = walkEast(fromEast = 100.0, count = 30)
        val turn = walkNorth(fromNorth = 8.0, count = 30, eastMeters = 300.0)
        val back = walkNorth(fromNorth = 8.0, count = 30, eastMeters = 300.0).reversed()
        val home = walkEast(fromEast = 100.0, count = 30).reversed()

        val result = MapMatcher.match(
            sessionId = SESSION_ID,
            samples = samples(out + turn + back + home),
            ways = listOf(mainStreet, crossStreet),
        )

        assertEquals(listOf(1L, 2L, 1L), result.map { it.wayId })
    }

    @Test
    fun どの道からも遠いサンプルは通過にならない() {
        val result = MapMatcher.match(
            sessionId = SESSION_ID,
            samples = samples(walkEast(fromEast = 20.0, count = 20, northMeters = 60.0)),
            ways = listOf(mainStreet),
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun 同じサンプル列を2回流しても同じ通過列になる() {
        val walk = samples(
            walkEast(fromEast = 20.0, count = 30) + walkNorth(fromNorth = 8.0, count = 30, eastMeters = 300.0),
        )
        val ways = listOf(mainStreet, crossStreet, backStreet)

        assertEquals(MapMatcher.match(SESSION_ID, walk, ways), MapMatcher.match(SESSION_ID, walk, ways))
    }

    @Test
    fun 入力の並び順が違っても同じ通過列になる() {
        val walk = samples(
            walkEast(fromEast = 20.0, count = 30) + walkNorth(fromNorth = 8.0, count = 30, eastMeters = 300.0),
        )
        val ways = listOf(mainStreet, crossStreet, backStreet)

        assertEquals(
            MapMatcher.match(SESSION_ID, walk, ways),
            MapMatcher.match(SESSION_ID, walk.reversed(), ways.reversed()),
        )
    }

    @Test
    fun 立ち止まっても通過は割れないが長く離れると割れる() {
        val firstHalf = samples(walkEast(fromEast = 20.0, count = 10))
        // 5分の休憩（ベンチで測位が止まった）を挟んで同じ道の続きへ
        val afterBreak = samples(
            points = walkEast(fromEast = 48.0, count = 10),
            startMs = START_MS + 10 * INTERVAL_MS + 300_000L,
        )

        val split = MapMatcher.match(SESSION_ID, firstHalf + afterBreak, listOf(mainStreet))
        assertEquals(listOf(1L, 1L), split.map { it.wayId })

        // 閾値を10分に伸ばせば1本になる（＝割れているのはこの閾値のせい）
        val merged = MapMatcher.match(
            sessionId = SESSION_ID,
            samples = firstHalf + afterBreak,
            ways = listOf(mainStreet),
            config = MapMatchingConfig(maxPassageGapMs = 600_000L),
        )
        assertEquals(listOf(1L), merged.map { it.wayId })
    }

    @Test
    fun wayマスタが空なら通過も空() {
        val result = MapMatcher.match(SESSION_ID, samples(walkEast(20.0, 10)), ways = emptyList())

        assertTrue(result.isEmpty())
    }

    @Test
    fun サンプルが空なら通過も空() {
        assertTrue(MapMatcher.match(SESSION_ID, emptyList(), listOf(mainStreet)).isEmpty())
    }

    @Test
    fun 同時刻のサンプルがあっても落ちない() {
        // 端末が同じ時刻で複数点を返すことは実際にある（速度計算が0除算になる形）
        val base = samples(walkEast(fromEast = 20.0, count = 10))
        val duplicated = base + base.map { it.copy(latitude = it.latitude + 0.00001) }

        val result = MapMatcher.match(SESSION_ID, duplicated, listOf(mainStreet))

        assertEquals(listOf(1L), result.map { it.wayId })
    }
}

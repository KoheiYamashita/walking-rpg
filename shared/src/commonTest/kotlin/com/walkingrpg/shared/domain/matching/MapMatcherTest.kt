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

        // minRunSamples = 1 ＝ ノイズ除去なし。1件の飛びで歩いていない裏道が塗られる
        // （本通りが2回にならないのは再通過の併合が効いているから＝別のテスト）
        val result = MapMatcher.match(
            sessionId = SESSION_ID,
            samples = samples(points),
            ways = listOf(mainStreet, backStreet),
            config = MapMatchingConfig(minRunSamples = 1),
        )

        assertEquals(listOf(1L, 3L), result.map { it.wayId })

        // 併合も切れば、飛び1件が本通りを2回に割る（3本に化ける形）
        val rawest = MapMatcher.match(
            sessionId = SESSION_ID,
            samples = samples(points),
            ways = listOf(mainStreet, backStreet),
            config = MapMatchingConfig(minRunSamples = 1, revisitMergeGapMs = 0L),
        )
        assertEquals(listOf(1L, 3L, 1L), rawest.map { it.wayId })
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

    // main street の6m北を並走する歩道（実対象圏のfootway 2本と同じ間隔）。
    // 中間（北3m）を歩くと、どちらも3mでヒステリシスが無いと札が揺れ続ける。
    private val sidewalk = eastWestWay(id = 5L, northMeters = 6.0, fromEast = 0.0, toEast = 300.0)

    // main street の途中（東150m）を south から north へ突っ切る道（交差点）
    private val crossingStreet =
        northSouthWay(id = 6L, eastMeters = 150.0, fromNorth = -100.0, toNorth = 100.0)

    @Test
    fun 並走する2本の中間で揺れても通過は片方1回だけ() {
        // 2本のちょうど中間（北3m）を、±1.5mでふらつきながら歩く。
        // 最寄りだけで決めると1サンプルおきに札が入れ替わる形。
        val northOffsets = List(30) { index -> if (index % 2 == 0) 1.5 else 4.5 }
        val points = northOffsets.mapIndexed { index, north -> point(north, 40.0 + index * 2.8) }

        val result = MapMatcher.match(SESSION_ID, samples(points), listOf(mainStreet, sidewalk))

        assertEquals(
            listOf(Passage(SESSION_ID, wayId = 1L, timestampMs = START_MS)),
            result,
            "先に乗った道に粘着し続ける（両方は塗らない）",
        )

        // 粘着を切ると、歩いていない並走路も塗られる（実散歩で観測した水増しそのもの）
        val withoutHysteresis = MapMatcher.match(
            sessionId = SESSION_ID,
            samples = samples(points),
            ways = listOf(mainStreet, sidewalk),
            config = MapMatchingConfig(hysteresisMarginMeters = 0.0, minRunSamples = 1),
        )
        assertEquals(listOf(1L, 5L), withoutHysteresis.map { it.wayId })
    }

    @Test
    fun 交差点で横断する道を数秒挟んでも元の道は1通過() {
        // 本通りを東へ。東150mで交差する道を横切るあいだ、数サンプルだけ北に8mずれて
        // そちらの方がはっきり近くなる（横断歩道の中心でよくある形）。
        val before = walkEast(fromEast = 100.0, count = 18) // 東100〜147.6m
        val crossing = List(3) { index -> point(8.0, 148.0 + index * 2.0) }
        val after = walkEast(fromEast = 154.0, count = 18)
        val ways = listOf(mainStreet, crossingStreet)

        val result = MapMatcher.match(SESSION_ID, samples(before + crossing + after), ways)

        assertEquals(
            1,
            result.count { it.wayId == 1L },
            "戻ってきた本通りは同じ通過の続き（60秒未満の再通過は併合）",
        )
        assertEquals(1, result.count { it.wayId == 6L }, "横断した道は1回ぶん数える")

        // 併合を切ると本通りが2回に化ける＝抑えているのはこの機構
        val withoutMerge = MapMatcher.match(
            sessionId = SESSION_ID,
            samples = samples(before + crossing + after),
            ways = ways,
            config = MapMatchingConfig(revisitMergeGapMs = 0L),
        )
        assertEquals(2, withoutMerge.count { it.wayId == 1L })
    }

    @Test
    fun 併合の閾値を超えて空けて戻ってくれば別の通過になる() {
        // 本通り → 交差する道を北へ2分 → 引き返して本通りへ。往復は2回のまま
        val out = walkEast(fromEast = 100.0, count = 30)
        val north = walkNorth(fromNorth = 8.0, count = 40, eastMeters = 300.0)
        val back = north.reversed()
        val home = out.reversed()

        val result = MapMatcher.match(
            sessionId = SESSION_ID,
            samples = samples(out + north + back + home),
            ways = listOf(mainStreet, crossStreet),
        )

        assertEquals(listOf(1L, 2L, 1L), result.map { it.wayId }, "往復は2回（設計どおり）")
        assertTrue(
            result.last().timestampMs - result.first().timestampMs >= 60_000L,
            "併合の閾値（60秒）を超えて離れている",
        )
    }

    @Test
    fun 境界の抑制を入れても同じ列からは同じ通過が出る() {
        val points = List(40) { index ->
            point(if (index % 2 == 0) 1.5 else 4.5, 40.0 + index * 2.8)
        } + walkNorth(fromNorth = 8.0, count = 30, eastMeters = 300.0)
        val walk = samples(points)
        val ways = listOf(mainStreet, sidewalk, crossStreet, backStreet)

        assertEquals(MapMatcher.match(SESSION_ID, walk, ways), MapMatcher.match(SESSION_ID, walk, ways))
        assertEquals(
            MapMatcher.match(SESSION_ID, walk, ways),
            MapMatcher.match(SESSION_ID, walk.reversed(), ways.reversed()),
            "入力の並び順にも依らない",
        )
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

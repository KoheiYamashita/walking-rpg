package com.walkingrpg.shared.domain.walk

import com.walkingrpg.shared.domain.map.GeoPoint
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 自動終了の判定（design.md §3「自宅付近＋移動停止」）。 */
class HomeArrivalDetectorTest {

    private val config = HomeArrivalConfig.DEFAULT

    private fun detector(home: GeoPoint? = HOME, startedAtMs: Long = 0L) = HomeArrivalDetector(
        home = home,
        sessionStartedAtMs = startedAtMs,
        config = config,
    )

    /** サンプル列を順に畳む（実際の記録ループと同じ流れ）。 */
    private fun HomeArrivalDetector.feed(samples: List<LocationSample>): HomeArrivalDetector =
        samples.fold(this) { state, sample -> state.sampleRecorded(sample) }

    @Test
    fun 自宅を出て戻って止まったら自動終了する() {
        val walk = listOf(
            sampleAt(ts = 0, metersFromHome = 0.0),
            sampleAt(ts = 60_000, metersFromHome = 300.0),
            sampleAt(ts = 600_000, metersFromHome = 900.0),
            // 帰宅
            sampleAt(ts = 1_200_000, metersFromHome = 10.0),
            // 玄関で止まったまま2分
            sampleAt(ts = 1_260_000, metersFromHome = 12.0),
            sampleAt(ts = 1_320_000, metersFromHome = 8.0),
        )

        assertTrue(detector().feed(walk).isArrived)
    }

    @Test
    fun 開始直後は自宅にいても終了しない() {
        // 出発前に玄関で身支度している状態。武装していないので何分いても終わらない
        val waiting = (0..20).map { sampleAt(ts = it * 60_000L, metersFromHome = 5.0) }

        val state = detector().feed(waiting)
        assertFalse(state.hasLeftHome)
        assertFalse(state.isArrived)
    }

    @Test
    fun 自宅圏外の停止では終了しない() {
        // 公園のベンチで30分休憩（信号待ちも同じ形）
        val rest = listOf(sampleAt(ts = 0, metersFromHome = 0.0)) +
            (0..30).map { sampleAt(ts = 60_000L + it * 60_000L, metersFromHome = 500.0) }

        val state = detector().feed(rest)
        assertTrue(state.hasLeftHome)
        assertFalse(state.isArrived)
    }

    @Test
    fun 自宅が未登録なら自動終了しない() {
        val walk = listOf(
            sampleAt(ts = 0, metersFromHome = 0.0),
            sampleAt(ts = 60_000, metersFromHome = 300.0),
            sampleAt(ts = 1_200_000, metersFromHome = 5.0),
            sampleAt(ts = 1_320_000, metersFromHome = 5.0),
            sampleAt(ts = 1_440_000, metersFromHome = 5.0),
        )

        assertFalse(detector(home = null).feed(walk).isArrived)
    }

    @Test
    fun 自宅圏内でも動き続けていれば終了しない() {
        // 自宅前の道を行ったり来たり（庭仕事・近所での立ち話）
        val nearHome = (0..20).map {
            sampleAt(ts = 600_000L + it * 30_000L, metersFromHome = if (it % 2 == 0) 5.0 else 60.0)
        }
        val walk = listOf(
            sampleAt(ts = 0, metersFromHome = 0.0),
            sampleAt(ts = 60_000, metersFromHome = 300.0),
        ) + nearHome

        assertFalse(detector().feed(walk).isArrived)
    }

    @Test
    fun 最小セッション時間に届かないうちは終了しない() {
        val start = listOf(
            sampleAt(ts = 0, metersFromHome = 0.0),
            // すぐ引き返した（武装だけはする）
            sampleAt(ts = 10_000, metersFromHome = 300.0),
            sampleAt(ts = 20_000, metersFromHome = 5.0),
        )
        // 停止時間（2分）は満たすが、セッションはまだ3分経っていない
        val tooEarly = detector().feed(start + sampleAt(ts = 150_000, metersFromHome = 5.0))
        assertFalse(tooEarly.isArrived)

        // 3分を超えたところで成立する
        val later = tooEarly.sampleRecorded(sampleAt(ts = 200_000, metersFromHome = 5.0))
        assertTrue(later.isArrived)
    }

    @Test
    fun 精度の悪いサンプルは判定に使わない() {
        val walk = listOf(
            sampleAt(ts = 0, metersFromHome = 0.0),
            sampleAt(ts = 60_000, metersFromHome = 300.0),
            // 屋内に入って測位が荒れた。自宅の真上に見えていても判定材料にしない
            sampleAt(ts = 1_200_000, metersFromHome = 0.0, accuracy = 80.0),
            sampleAt(ts = 1_320_000, metersFromHome = 0.0, accuracy = 80.0),
            sampleAt(ts = 1_440_000, metersFromHome = 0.0, accuracy = 80.0),
        )

        assertFalse(detector().feed(walk).isArrived)
    }

    @Test
    fun 一度成立したら以降のサンプルで戻らない() {
        val arrived = detector().feed(
            listOf(
                sampleAt(ts = 0, metersFromHome = 0.0),
                sampleAt(ts = 60_000, metersFromHome = 300.0),
                sampleAt(ts = 1_200_000, metersFromHome = 5.0),
                sampleAt(ts = 1_320_000, metersFromHome = 5.0),
            ),
        )
        assertTrue(arrived.isArrived)

        // 畳んだあとに遅れて届いたサンプル（別の場所）でも状態は動かない
        assertTrue(arrived.sampleRecorded(sampleAt(ts = 1_400_000, metersFromHome = 900.0)).isArrived)
    }

    @Test
    fun toStringに自宅座標は出ない() {
        // 秘密を持つ data class は既定 toString を使わない（SetupModelsTest と同じ約束）
        val text = HomeArrivalDetector(home = HOME, sessionStartedAtMs = 0L).toString()
        assertFalse(text.contains(HOME.latitude.toString()))
        assertFalse(text.contains(HOME.longitude.toString()))
    }

    private companion object {
        val HOME = GeoPoint(latitude = 35.0, longitude = 139.0)

        /** 子午線上の1度の長さ（m）。真北にずらすので経度のスケールを考えなくてよい。 */
        const val METERS_PER_DEGREE_LATITUDE = 111_194.9

        /** 自宅から真北に [metersFromHome] だけ離れた点。 */
        fun sampleAt(ts: Long, metersFromHome: Double, accuracy: Double = 5.0) = LocationSample(
            sessionId = 1L,
            timestampMs = ts,
            latitude = HOME.latitude + metersFromHome / METERS_PER_DEGREE_LATITUDE,
            longitude = HOME.longitude,
            accuracyMeters = accuracy,
        )
    }
}

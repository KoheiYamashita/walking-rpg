package com.walkingrpg.shared.domain.matching.replay

import com.walkingrpg.shared.domain.matching.MapMatcher
import com.walkingrpg.shared.domain.matching.MapMatchingConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * GPSリプレイテスト（architecture.md §7）。
 *
 * フィクスチャ（サンプル列＋wayマスタ＋期待する通過列）を全件流して、
 * ロジックや閾値を触ったときに結果が変わっていないかを見る。
 * **落ちたときに直すのはコードとは限らない**：閾値を意図的に変えたなら、
 * 期待値の方を目視で確認して更新する（そのための `expectedPassages`）。
 *
 * 実散歩のログはまだ入っていない。追加手順は README.md。
 */
class MapMatchingReplayTest {

    @Test
    fun フィクスチャの通過列が期待どおりになる() {
        GpsReplayFixtures.ALL.forEach { raw ->
            val fixture = GpsReplayFixtureLoader.load(raw)
            val actual = MapMatcher.match(
                sessionId = fixture.session.sessionId,
                samples = fixture.toSamples(),
                ways = fixture.toWays(),
                config = MapMatchingConfig.DEFAULT,
            )
            assertEquals(fixture.toExpectedPassages(), actual, "フィクスチャ: ${fixture.name}")
        }
    }

    @Test
    fun フィクスチャを2回流しても同じ通過列になる() {
        GpsReplayFixtures.ALL.forEach { raw ->
            val fixture = GpsReplayFixtureLoader.load(raw)
            val samples = fixture.toSamples()
            val ways = fixture.toWays()

            val first = MapMatcher.match(fixture.session.sessionId, samples, ways)
            val second = MapMatcher.match(fixture.session.sessionId, samples.reversed(), ways.reversed())

            assertEquals(first, second, "フィクスチャ: ${fixture.name}")
        }
    }

    @Test
    fun フィクスチャが読める形式である() {
        assertTrue(GpsReplayFixtures.ALL.isNotEmpty())
        GpsReplayFixtures.ALL.forEach { raw ->
            val fixture = GpsReplayFixtureLoader.load(raw)
            assertTrue(fixture.ways.isNotEmpty(), "${fixture.name}: wayマスタが空")
            assertTrue(fixture.session.samples.isNotEmpty(), "${fixture.name}: サンプルが空")
            // 時刻順であること（エクスポートは ORDER BY ts なので、崩れていたら貼り間違い）
            val timestamps = fixture.session.samples.map { it.ts }
            assertEquals(timestamps.sorted(), timestamps, "${fixture.name}: サンプルが時刻順でない")
        }
    }
}

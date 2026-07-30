package com.walkingrpg.shared.domain.snapshot

import com.walkingrpg.shared.data.FakeWalkSessionRepository
import com.walkingrpg.shared.data.MutableClock
import com.walkingrpg.shared.domain.FakeCodexProgressRepository
import com.walkingrpg.shared.domain.FakeOsmMasterRepository
import com.walkingrpg.shared.domain.FakePassageRepository
import com.walkingrpg.shared.domain.FakeWayGrowthRepository
import com.walkingrpg.shared.domain.growth.GrowthStage
import com.walkingrpg.shared.domain.growth.WayGrowth
import com.walkingrpg.shared.domain.matching.Passage
import com.walkingrpg.shared.domain.testWay
import com.walkingrpg.shared.domain.walk.SessionEndReason
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 月次スナップショットの生成（[GenerateMonthlySnapshotsUseCase]）。
 *
 * 見たいのは6つ：
 * - 当月は作らない／散歩が無い月は作らない
 * - 開かなかった月の穴も塞ぐ（先月ぶんだけ作るのではない）
 * - 何度呼んでも増えない（冪等）
 * - 既にある月は描き直さない
 * - **画像 → DBの行**の順に書く（行だけ残ってファイルが無い状態を作らない）
 *
 * 暦は [SyntheticCalendarDays]（1ヶ月 = 1000ms）で潰す＝境界が数字で読める。
 * 本物の暦の正しさは `SystemCalendarDaysTest`（androidUnitTest）が見ている。
 */
class GenerateMonthlySnapshotsUseCaseTest {

    /** 「今」＝2026-07 の1日目。先月は 2026-06。 */
    private val nowMs = SyntheticCalendarDays.monthStartMs("2026-07")

    private class Fixture(nowMs: Long) {
        val sessions = FakeWalkSessionRepository()
        val passages = FakePassageRepository()
        val osm = FakeOsmMasterRepository(ways = listOf(testWay(id = 1L)))
        val growths = FakeWayGrowthRepository(
            listOf(WayGrowth(wayId = 1L, passCount = 3, stage = GrowthStage.FLOWER)),
        )
        val codex = FakeCodexProgressRepository()
        val snapshots = FakeSnapshotRepository()
        val images = FakeSnapshotImageStore()
        val renderer = FakeMonthlySnapshotRenderer()
        val calendarDays = SyntheticCalendarDays(nowMs)

        /** その月の1日目に始まって終わった散歩を1件足す。 */
        suspend fun addSession(monthIso: String): Long {
            val startedAtMs = SyntheticCalendarDays.monthStartMs(monthIso)
            val sessionId = sessions.startSession(startedAtMs)
            sessions.endSession(sessionId, startedAtMs + 100L, SessionEndReason.MANUAL)
            passages.sessionPassages = passages.sessionPassages +
                (sessionId to listOf(Passage(sessionId, wayId = 1L, timestampMs = startedAtMs)))
            return sessionId
        }

        fun useCase(clockMs: Long = 999_999L) = GenerateMonthlySnapshotsUseCase(
            walkSessionRepository = sessions,
            snapshotRepository = snapshots,
            getMonthlySnapshotScene = GetMonthlySnapshotSceneUseCase(growths, osm),
            getMonthlySnapshotStats = GetMonthlySnapshotStatsUseCase(
                walkSessionRepository = sessions,
                passageRepository = passages,
                osmMasterRepository = osm,
                codexProgressRepository = codex,
                calendarDays = calendarDays,
                catalog = emptyList(),
            ),
            renderer = renderer,
            imageStore = images,
            calendarDays = calendarDays,
            clock = MutableClock(clockMs),
        )
    }

    private fun fixture() = Fixture(nowMs)

    @Test
    fun 散歩が1件も無ければ何も作らない() = runTest {
        val fixture = fixture()

        assertEquals(emptyList(), fixture.useCase().invoke())
        assertEquals(0, fixture.snapshots.count())
        assertEquals(emptyList(), fixture.renderer.renderedMonths, "描画も走らない")
    }

    @Test
    fun 先月ぶんの1枚が作られる() = runTest {
        val fixture = fixture()
        fixture.addSession("2026-06")

        assertEquals(listOf(CalendarMonth("2026-06")), fixture.useCase(clockMs = 42L).invoke())

        val saved = fixture.snapshots.snapshot(CalendarMonth("2026-06"))!!
        assertEquals("snapshots/2026-06.png", saved.imagePath)
        assertEquals(1, saved.stats.sessionCount)
        assertEquals(42L, saved.createdAtMs, "作った時刻は Clock 由来")
        assertEquals(setOf("snapshots/2026-06.png"), fixture.images.paths)
    }

    @Test
    fun 当月は作らない() = runTest {
        val fixture = fixture()
        // 記録を始めたのが今月＝先月より前に遡る月が無い
        fixture.addSession("2026-07")

        assertEquals(emptyList(), fixture.useCase().invoke())
        assertEquals(0, fixture.snapshots.count(), "終わっていない月の1枚は焼かない")
    }

    @Test
    fun 当月に散歩があっても先月ぶんだけ作る() = runTest {
        val fixture = fixture()
        fixture.addSession("2026-06")
        fixture.addSession("2026-07")

        assertEquals(listOf(CalendarMonth("2026-06")), fixture.useCase().invoke())
    }

    @Test
    fun 開かなかった月の穴も塞ぐ() = runTest {
        val fixture = fixture()
        // 3月・4月・6月に歩いて、7月まで一度もアプリを開かなかった
        fixture.addSession("2026-03")
        fixture.addSession("2026-04")
        fixture.addSession("2026-06")

        assertEquals(
            listOf(CalendarMonth("2026-03"), CalendarMonth("2026-04"), CalendarMonth("2026-06")),
            fixture.useCase().invoke(),
            "古い月から順に、まとめて作る",
        )
        // 5月は散歩が無いので行を作らない（空白の月を挟まない）
        assertEquals(
            listOf("2026-06", "2026-04", "2026-03"),
            fixture.snapshots.snapshots().map { it.month.iso },
            "アルバムの並びは新しい月から",
        )
    }

    @Test
    fun 散歩が1件も無い月は飛ばす() = runTest {
        val fixture = fixture()
        fixture.addSession("2026-04")

        fixture.useCase().invoke()

        assertEquals(
            listOf(CalendarMonth("2026-04")),
            fixture.renderer.renderedMonths,
            "歩かなかった5月・6月は描画すらしない",
        )
        assertEquals(setOf("snapshots/2026-04.png"), fixture.images.paths)
    }

    @Test
    fun 何度呼んでも増えない() = runTest {
        val fixture = fixture()
        fixture.addSession("2026-05")
        fixture.addSession("2026-06")

        val first = fixture.useCase().invoke()
        val second = fixture.useCase().invoke()

        assertEquals(2, first.size)
        assertEquals(emptyList(), second, "2回目は何も作らない")
        assertEquals(2, fixture.snapshots.count())
        assertEquals(2, fixture.renderer.renderedMonths.size, "既にある月は描き直さない")
        assertEquals(2, fixture.images.writtenPaths.size, "画像も書き直さない")
    }

    @Test
    fun 既にある月は数値も画像も触らない() = runTest {
        val fixture = fixture()
        fixture.addSession("2026-06")
        fixture.snapshots.insertIfAbsent(
            Snapshot(
                month = CalendarMonth("2026-06"),
                imagePath = "snapshots/2026-06.png",
                stats = MonthlySnapshotStats(
                    distanceMeters = 1_234.0,
                    newWayCount = 7,
                    discoveredSpeciesNames = listOf("むかしの記録"),
                    sessionCount = 9,
                ),
                createdAtMs = 1L,
            ),
        )
        fixture.snapshots.insertAttempts.clear()

        assertEquals(emptyList(), fixture.useCase().invoke())

        val saved = fixture.snapshots.snapshot(CalendarMonth("2026-06"))!!
        assertEquals(1_234.0, saved.stats.distanceMeters, "焼いた数値は書き換わらない")
        assertEquals(1L, saved.createdAtMs)
        assertEquals(emptyList(), fixture.snapshots.insertAttempts, "挿入すら試みない")
        assertEquals(emptyList(), fixture.images.writtenPaths, "画像も上書きしない")
    }

    @Test
    fun 画像を書いてからDBの行を入れる() = runTest {
        val fixture = fixture()
        fixture.addSession("2026-06")

        fixture.useCase().invoke()

        assertEquals(
            listOf("snapshots/2026-06.png"),
            fixture.images.writtenPaths,
            "画像が書かれている",
        )
        assertEquals(
            listOf(CalendarMonth("2026-06")),
            fixture.snapshots.insertAttempts,
            "そのあとに行が入る",
        )
    }

    @Test
    fun 画像が書けなければ行を作らない() = runTest {
        val fixture = fixture()
        fixture.addSession("2026-06")
        val useCase = GenerateMonthlySnapshotsUseCase(
            walkSessionRepository = fixture.sessions,
            snapshotRepository = fixture.snapshots,
            getMonthlySnapshotScene = GetMonthlySnapshotSceneUseCase(fixture.growths, fixture.osm),
            getMonthlySnapshotStats = GetMonthlySnapshotStatsUseCase(
                walkSessionRepository = fixture.sessions,
                passageRepository = fixture.passages,
                osmMasterRepository = fixture.osm,
                codexProgressRepository = fixture.codex,
                calendarDays = fixture.calendarDays,
                catalog = emptyList(),
            ),
            renderer = fixture.renderer,
            imageStore = FakeSnapshotImageStore(failWith = IllegalStateException("書けない")),
            calendarDays = fixture.calendarDays,
            clock = MutableClock(0L),
        )

        assertFailsWith<IllegalStateException> { useCase() }
        assertEquals(0, fixture.snapshots.count(), "アルバムが割れない（行だけ残らない）")
        assertEquals(emptyList(), fixture.snapshots.insertAttempts)
    }

    @Test
    fun 描画に失敗したら画像も行も作らない() = runTest {
        val fixture = fixture()
        fixture.addSession("2026-06")
        val useCase = GenerateMonthlySnapshotsUseCase(
            walkSessionRepository = fixture.sessions,
            snapshotRepository = fixture.snapshots,
            getMonthlySnapshotScene = GetMonthlySnapshotSceneUseCase(fixture.growths, fixture.osm),
            getMonthlySnapshotStats = GetMonthlySnapshotStatsUseCase(
                walkSessionRepository = fixture.sessions,
                passageRepository = fixture.passages,
                osmMasterRepository = fixture.osm,
                codexProgressRepository = fixture.codex,
                calendarDays = fixture.calendarDays,
                catalog = emptyList(),
            ),
            renderer = FakeMonthlySnapshotRenderer(failWith = IllegalStateException("描けない")),
            imageStore = fixture.images,
            calendarDays = fixture.calendarDays,
            clock = MutableClock(0L),
        )

        assertFailsWith<IllegalStateException> { useCase() }
        assertTrue(fixture.images.paths.isEmpty())
        assertEquals(0, fixture.snapshots.count())
    }

    @Test
    fun 絵は成長済みの全wayから組む() = runTest {
        val fixture = fixture()
        fixture.addSession("2026-06")

        fixture.useCase().invoke()

        // 当月に歩いたかどうかで道を絞らない（SnapshotScene のKDoc「その月までに育った街」）
        val scene = GetMonthlySnapshotSceneUseCase(fixture.growths, fixture.osm)
            .invoke(CalendarMonth("2026-06"))
        assertEquals(1, scene.ways.size)
        assertEquals(GrowthStage.FLOWER, scene.ways.single().stage)
    }
}

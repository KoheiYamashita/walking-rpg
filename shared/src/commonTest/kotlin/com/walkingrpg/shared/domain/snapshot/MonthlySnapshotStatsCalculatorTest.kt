package com.walkingrpg.shared.domain.snapshot

import com.walkingrpg.shared.domain.codex.CodexCategory
import com.walkingrpg.shared.domain.codex.CodexProgress
import com.walkingrpg.shared.domain.codex.ForeshadowStage
import com.walkingrpg.shared.domain.codex.Species
import com.walkingrpg.shared.domain.matching.SessionVisit
import com.walkingrpg.shared.domain.matching.SyntheticWalk
import com.walkingrpg.shared.domain.review.WalkDistanceCalculator
import com.walkingrpg.shared.domain.walk.LocationSample
import com.walkingrpg.shared.domain.walk.SessionEndReason
import com.walkingrpg.shared.domain.walk.WalkSession
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * その月の数値の数え方（[MonthlySnapshotStatsCalculator]）。
 *
 * 見たいのは4つ：
 * - 月の境界（`[月初, 翌月初)`）で切れること
 * - セッションの帰属が **`started_at`** であること（月をまたいだ散歩は出た月）
 * - 「新しい道」＝**初回通過が当月**の道であること
 * - 距離が振り返り（`WalkDistanceCalculator`）の足し算と一致すること
 */
class MonthlySnapshotStatsCalculatorTest {

    /** 2026-06 の範囲（テストの中では素の millis で組む＝暦の計算は CalendarDays の担当）。 */
    private val june = MonthRange(fromMs = 6_000L, untilMs = 7_000L)

    private val skimmer = Species(
        id = "water_skimmer",
        name = "テストトンボ",
        category = CodexCategory.WATER,
        requiredVisitCount = 3,
        foreshadowText = "水面の上を何かが行き来している。",
    )
    private val kingfisher = Species(
        id = "water_kingfisher",
        name = "テストカワセミ",
        category = CodexCategory.WATER,
        requiredVisitCount = 10,
        foreshadowText = "青い羽根が落ちている。",
    )
    private val catalog = listOf(skimmer, kingfisher)

    private fun session(id: Long, startedAtMs: Long) = WalkSession(
        id = id,
        startedAtMs = startedAtMs,
        endedAtMs = startedAtMs + 100L,
        endReason = SessionEndReason.MANUAL,
    )

    private fun stats(
        sessions: List<WalkSession> = emptyList(),
        samplesBySession: Map<Long, List<LocationSample>> = emptyMap(),
        sessionVisitsByWay: Map<Long, List<SessionVisit>> = emptyMap(),
        codexProgresses: List<CodexProgress> = emptyList(),
    ) = MonthlySnapshotStatsCalculator.stats(
        range = june,
        sessions = sessions,
        samplesBySession = samplesBySession,
        sessionVisitsByWay = sessionVisitsByWay,
        codexProgresses = codexProgresses,
        catalog = catalog,
    )

    /** [meters] メートルを東へ歩いた軌跡（10m刻み＝アンカー閾値5mをはっきり超える）。 */
    private fun walk(sessionId: Long, meters: Double): List<LocationSample> = SyntheticWalk.samples(
        points = List((meters / 10.0).toInt() + 1) { index ->
            SyntheticWalk.point(northMeters = 0.0, eastMeters = index * 10.0)
        },
    ).map { it.copy(sessionId = sessionId) }

    @Test
    fun 散歩が無い月は全部ゼロ() {
        assertEquals(
            MonthlySnapshotStats(
                distanceMeters = 0.0,
                newWayCount = 0,
                discoveredSpeciesNames = emptyList(),
                sessionCount = 0,
            ),
            stats(),
        )
    }

    @Test
    fun 散歩の月の帰属は開始時刻で決まる() {
        val sessions = listOf(
            session(id = 1L, startedAtMs = 5_999L), // 先月の最終日
            session(id = 2L, startedAtMs = 6_000L), // 月初 0:00 ちょうど
            // 月末に出て翌月に帰った散歩。ended_at で切ると当月から漏れる
            session(id = 3L, startedAtMs = 6_999L).copy(endedAtMs = 7_500L),
            session(id = 4L, startedAtMs = 7_000L), // 翌月初 0:00 ちょうど
        )

        assertEquals(2, stats(sessions = sessions).sessionCount)
    }

    @Test
    fun 距離は当月のセッションぶんだけを振り返りと同じ物差しで足す() {
        val inMonth = session(id = 1L, startedAtMs = 6_100L)
        val alsoInMonth = session(id = 2L, startedAtMs = 6_500L)
        val outOfMonth = session(id = 3L, startedAtMs = 5_000L)
        val samples = mapOf(
            1L to walk(sessionId = 1L, meters = 120.0),
            2L to walk(sessionId = 2L, meters = 80.0),
            3L to walk(sessionId = 3L, meters = 500.0),
        )

        val result = stats(
            sessions = listOf(inMonth, alsoInMonth, outOfMonth),
            samplesBySession = samples,
        )

        assertEquals(200.0, result.distanceMeters, 1.0, "当月ぶん（120 + 80）だけ")
        // 振り返り側を1回ずつ呼んで足したものと同じ数（月合計と振り返りの足し算が合う）
        assertEquals(
            WalkDistanceCalculator.distanceMeters(samples.getValue(1L)) +
                WalkDistanceCalculator.distanceMeters(samples.getValue(2L)),
            result.distanceMeters,
        )
    }

    @Test
    fun 新しい道は初回通過が当月の道だけ() {
        val visits = mapOf(
            // 先月から通っている道（当月にも通った）＝新しくない
            1L to listOf(
                SessionVisit(sessionId = 1L, firstTimestampMs = 5_500L),
                SessionVisit(sessionId = 2L, firstTimestampMs = 6_100L),
            ),
            // 当月にはじめて通った道
            2L to listOf(SessionVisit(sessionId = 2L, firstTimestampMs = 6_200L)),
            // 当月にはじめて通って、翌月も通った道＝当月の1本
            3L to listOf(
                SessionVisit(sessionId = 2L, firstTimestampMs = 6_900L),
                SessionVisit(sessionId = 3L, firstTimestampMs = 7_100L),
            ),
            // 翌月にはじめて通った道
            4L to listOf(SessionVisit(sessionId = 3L, firstTimestampMs = 7_200L)),
        )

        assertEquals(2, stats(sessionVisitsByWay = visits).newWayCount)
    }

    @Test
    fun 通過が1件も無い道は新しい道に数えない() {
        // sessionVisitsByWay は通過0件の道を返さないが、空リストが来ても数えないこと
        assertEquals(0, stats(sessionVisitsByWay = mapOf(1L to emptyList())).newWayCount)
    }

    @Test
    fun 発見した種は当月に発見したものだけをカタログ順で返す() {
        val progresses = listOf(
            // 順序を逆に置いても、返るのはカタログの並び（トンボ→カワセミ）
            CodexProgress(
                speciesId = kingfisher.id,
                visitCount = 10,
                foreshadowStage = ForeshadowStage.NONE,
                discoveredAtMs = 6_500L,
            ),
            CodexProgress(
                speciesId = skimmer.id,
                visitCount = 3,
                foreshadowStage = ForeshadowStage.NONE,
                discoveredAtMs = 6_100L,
            ),
        )

        assertEquals(
            listOf(skimmer.name, kingfisher.name),
            stats(codexProgresses = progresses).discoveredSpeciesNames,
        )
    }

    @Test
    fun 先月発見した種と未発見の種は数えない() {
        val progresses = listOf(
            CodexProgress(
                speciesId = skimmer.id,
                visitCount = 3,
                foreshadowStage = ForeshadowStage.NONE,
                discoveredAtMs = 5_900L,
            ),
            CodexProgress(
                speciesId = kingfisher.id,
                visitCount = 8,
                foreshadowStage = ForeshadowStage.NEAR,
            ),
        )

        assertEquals(emptyList(), stats(codexProgresses = progresses).discoveredSpeciesNames)
    }

    @Test
    fun カタログに無い種は落ちる() {
        // 種を消したあとに残った古い行（codex_progress は導出なので次の再計算で消える）
        val progresses = listOf(
            CodexProgress(
                speciesId = "removed_species",
                visitCount = 5,
                foreshadowStage = ForeshadowStage.NONE,
                discoveredAtMs = 6_100L,
            ),
        )

        assertEquals(emptyList(), stats(codexProgresses = progresses).discoveredSpeciesNames)
    }

    @Test
    fun 同じ入力からは必ず同じ数値が出る() {
        val sessions = listOf(session(id = 1L, startedAtMs = 6_100L))
        val samples = mapOf(1L to walk(sessionId = 1L, meters = 120.0))
        val visits = mapOf(1L to listOf(SessionVisit(sessionId = 1L, firstTimestampMs = 6_100L)))

        val first = stats(sessions, samples, visits)
        val second = stats(sessions, samples, visits)

        assertEquals(first, second)
    }
}

package com.walkingrpg.shared.domain.codex

import com.walkingrpg.shared.domain.FakeCodexProgressRepository
import com.walkingrpg.shared.domain.FakeOsmMasterRepository
import com.walkingrpg.shared.domain.FakePassageRepository
import com.walkingrpg.shared.domain.matching.SyntheticWalk
import com.walkingrpg.shared.domain.osm.PoiKind
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 図鑑の作り直し（[RecomputeCodexProgressUseCase]）。
 *
 * 判定の中身は [CodexProgressCalculatorTest] が見る。ここで見るのは
 * 「全削除→挿入で毎回作り直す」ことと「何度流しても同じ状態に収束する」こと
 * （＝いつ捨ててもよいキャッシュであることの担保）。
 */
class RecomputeCodexProgressUseCaseTest {

    private val passages = FakePassageRepository()
    private val master = FakeOsmMasterRepository()
    private val codex = FakeCodexProgressRepository()

    private val riverside = SyntheticWalk.eastWestWay(
        id = 1L,
        northMeters = 0.0,
        fromEast = 0.0,
        toEast = 300.0,
    )

    private fun useCase(config: CodexConfig = CodexConfig.DEFAULT) = RecomputeCodexProgressUseCase(
        passageRepository = passages,
        osmMasterRepository = master,
        codexProgressRepository = codex,
        config = config,
    )

    /** 道沿い10mに水辺POIを1件置いて、[sessionCount] 回の散歩ぶんの訪問を作る。 */
    private suspend fun givenRiverWalks(sessionCount: Int) {
        master.save(
            ways = listOf(riverside),
            pois = listOf(codexPoi("node/1", PoiKind.WATER, northMeters = 10.0, eastMeters = 150.0)),
        )
        passages.sessionVisits = mapOf(
            riverside.id to visits(
                *Array(sessionCount) { index -> (index + 1).toLong() to index * 60L },
            ),
        )
    }

    @Test
    fun 通過から進捗を作って全件置き換える() = runTest {
        givenRiverWalks(sessionCount = 3)

        val result = useCase().invoke()

        // 本番のカタログを使うので水辺の3回の種（シオカラトンボ）が出る
        assertTrue(result.isNotEmpty())
        assertEquals(result, codex.progresses)
        assertEquals(1, codex.replaceCount)
        assertTrue(
            result.any { it.isDiscovered },
            "3回通えば水辺のいちばん近い種は出ている（SpeciesCatalog）",
        )
    }

    @Test
    fun 何度流しても同じ状態に収束する() = runTest {
        givenRiverWalks(sessionCount = 3)
        val useCase = useCase()

        val first = useCase()
        val second = useCase()

        assertEquals(first, second)
        assertEquals(2, codex.replaceCount, "毎回作り直す（差分更新はしない）")
    }

    @Test
    fun 進捗を消してから流しても同じ状態が戻る() = runTest {
        givenRiverWalks(sessionCount = 3)
        val expected = useCase().invoke()

        codex.replaceAllProgresses(emptyList())
        val recovered = useCase().invoke()

        assertEquals(expected, recovered)
    }

    @Test
    fun 通過が消えたら進捗も消える() = runTest {
        givenRiverWalks(sessionCount = 3)
        useCase().invoke()
        assertTrue(codex.progresses.isNotEmpty())

        // セッションを全部削除した状況
        passages.sessionVisits = emptyMap()
        val result = useCase().invoke()

        assertTrue(result.isEmpty(), "歩いていない場所の生き物は残らない")
        assertTrue(codex.progresses.isEmpty())
    }

    @Test
    fun POIマスタが空なら何も作らない() = runTest {
        master.save(ways = listOf(riverside), pois = emptyList())
        passages.sessionVisits = mapOf(riverside.id to visits(1L to 0L, 2L to 60L, 3L to 120L))

        assertTrue(useCase().invoke().isEmpty())
        assertEquals(1, codex.replaceCount, "0件でも保存する（前の状態を消すため）")
    }

    @Test
    fun 近接半径を狭めると訪問が外れる() = runTest {
        // 閾値・半径を変えたら再計算だけで結果が変わる＝マイグレーション不要（CodexConfig のKDoc）
        givenRiverWalks(sessionCount = 3)

        val narrow = useCase(CodexConfig(poiProximityMeters = 5.0)).invoke()

        assertTrue(narrow.isEmpty(), "POIから10m離れた道は近傍でなくなる")
    }

    @Test
    fun 種はカタログから来るので進捗のIDは既知の種だけ() = runTest {
        givenRiverWalks(sessionCount = 3)

        val result = useCase().invoke()

        result.forEach { progress ->
            assertTrue(
                SpeciesCatalog.species(progress.speciesId) != null,
                "${progress.speciesId} がカタログに無い",
            )
        }
        assertTrue(
            result.all { SpeciesCatalog.species(it.speciesId)?.category == CodexCategory.WATER },
            "水辺しか通っていないので水辺の棚だけが進む",
        )
    }
}

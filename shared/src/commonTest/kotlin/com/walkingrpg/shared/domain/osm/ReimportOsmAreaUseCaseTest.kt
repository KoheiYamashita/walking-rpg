package com.walkingrpg.shared.domain.osm

import com.walkingrpg.shared.domain.codex.CodexProgress
import com.walkingrpg.shared.domain.codex.CodexProgressRepository
import com.walkingrpg.shared.domain.codex.RecomputeCodexProgressUseCase
import com.walkingrpg.shared.domain.map.GeoPoint
import com.walkingrpg.shared.domain.matching.Passage
import com.walkingrpg.shared.domain.matching.PassageRepository
import com.walkingrpg.shared.domain.matching.SessionVisit
import com.walkingrpg.shared.domain.walk.CurrentLocationRepository
import com.walkingrpg.shared.domain.walk.LocationFix
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * 再取り込みUseCaseの検証。座標はすべて架空。
 *
 * 中身（どのwayを拾うか・どの種が出るか）は [ImportOsmAreaUseCaseTest] と
 * `RecomputeCodexProgressUseCaseTest` が見ているので、ここで見たいのは順序と
 * 失敗時の振る舞いだけ：
 * - マスタの入れ替え → 図鑑進捗の作り直し、の順であること
 *   （逆に流すと、古いPOIから作った進捗が新しいマスタに残る）
 * - 取り込みが失敗したら作り直しに進まないこと
 *   （古いマスタ＋新しい進捗の組み合わせを作らない）
 */
class ReimportOsmAreaUseCaseTest {

    private val center = GeoPoint(latitude = 12.0, longitude = 34.0)

    /** 何がどの順で書かれたかを1本の列で見る（順序の検証はこれ1つで足りる）。 */
    private val log = mutableListOf<String>()

    private val snapshot = OsmAreaSnapshot(
        ways = listOf(
            OsmWayCandidate(
                id = 1L,
                name = null,
                highway = "residential",
                geometry = listOf(center, GeoPoint(center.latitude, center.longitude + 0.001)),
            ),
        ),
        pois = listOf(
            OsmPoiCandidate(
                id = "node/1",
                tags = mapOf("leisure" to "park"),
                location = GeoPoint(center.latitude + 0.0005, center.longitude + 0.0005),
            ),
        ),
    )

    @Test
    fun 取り込みのあとに図鑑進捗を作り直す() = runTest {
        val useCase = useCase()

        val result = useCase()

        assertEquals(1, result.wayCount)
        assertEquals(1, result.poiCount)
        assertEquals(listOf("area.fetch", "master.save", "codex.replaceAll"), log)
    }

    @Test
    fun 取り込みが失敗したら図鑑進捗を触らない() = runTest {
        // 現在地が取れない＝通信もDB書き込みもせずに止まる（ImportOsmAreaUseCase のKDoc）
        val useCase = useCase(fix = null)

        assertFailsWith<OsmAreaCenterUnavailableException> { useCase() }

        assertEquals(emptyList(), log)
    }

    @Test
    fun 半径はそのまま取り込みに渡る() = runTest {
        val areaSource = LoggingAreaSource(snapshot, log)
        val useCase = useCase(areaSource = areaSource)

        useCase(radiusMeters = 800)

        assertEquals(OsmArea(center = center, radiusMeters = 800), areaSource.requestedArea)
    }

    private fun useCase(
        areaSource: OsmAreaSource = LoggingAreaSource(snapshot, log),
        fix: LocationFix? = LocationFix(
            timestampMs = 0L,
            latitude = center.latitude,
            longitude = center.longitude,
            accuracyMeters = 8.0,
        ),
    ): ReimportOsmAreaUseCase {
        val masterRepository = LoggingMasterRepository(log)
        val locationRepository = FixedCurrentLocationRepository(fix)
        return ReimportOsmAreaUseCase(
            importOsmArea = ImportOsmAreaUseCase(
                areaSource = areaSource,
                masterRepository = masterRepository,
                currentLocationRepository = locationRepository,
            ),
            recomputeCodexProgress = RecomputeCodexProgressUseCase(
                passageRepository = EmptyPassageRepository(),
                osmMasterRepository = masterRepository,
                codexProgressRepository = LoggingCodexProgressRepository(log),
            ),
        )
    }
}

private class LoggingAreaSource(
    private val snapshot: OsmAreaSnapshot,
    private val log: MutableList<String>,
) : OsmAreaSource {
    var requestedArea: OsmArea? = null

    override suspend fun fetchArea(area: OsmArea): OsmAreaSnapshot {
        requestedArea = area
        log += "area.fetch"
        return snapshot
    }
}

/** マスタは書き込み順だけを見たいので、保持は最小限（図鑑の再計算が読む口も兼ねる）。 */
private class LoggingMasterRepository(
    private val log: MutableList<String>,
) : OsmMasterRepository {
    private var ways: List<Way> = emptyList()
    private var pois: List<Poi> = emptyList()

    override suspend fun save(ways: List<Way>, pois: List<Poi>) {
        this.ways = ways
        this.pois = pois
        log += "master.save"
    }

    override suspend fun counts(): OsmMasterCounts = OsmMasterCounts(ways.size, pois.size)
    override suspend fun ways(): List<Way> = ways
    override suspend fun pois(): List<Poi> = pois
}

private class LoggingCodexProgressRepository(
    private val log: MutableList<String>,
) : CodexProgressRepository {
    override suspend fun replaceAllProgresses(progresses: List<CodexProgress>) {
        log += "codex.replaceAll"
    }

    override suspend fun progresses(): List<CodexProgress> = emptyList()
    override suspend fun progress(speciesId: String): CodexProgress? = null
}

/** 通過が1件も無い状態（図鑑進捗は0件になるが、作り直しが走ることは見える）。 */
private class EmptyPassageRepository : PassageRepository {
    override suspend fun replaceSessionPassages(sessionId: Long, passages: List<Passage>) = Unit
    override suspend fun passages(sessionId: Long): List<Passage> = emptyList()
    override suspend fun passCountsByWay(): Map<Long, Int> = emptyMap()
    override suspend fun sessionVisitsByWay(): Map<Long, List<SessionVisit>> = emptyMap()
}

private class FixedCurrentLocationRepository(
    private val fix: LocationFix?,
) : CurrentLocationRepository {
    override suspend fun currentFix(): LocationFix? = fix
}

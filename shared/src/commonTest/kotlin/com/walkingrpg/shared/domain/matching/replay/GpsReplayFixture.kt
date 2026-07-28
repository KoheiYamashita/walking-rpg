package com.walkingrpg.shared.domain.matching.replay

import com.walkingrpg.shared.data.osm.WayGeometryCodec
import com.walkingrpg.shared.domain.matching.Passage
import com.walkingrpg.shared.domain.osm.GeoDistance
import com.walkingrpg.shared.domain.osm.Way
import com.walkingrpg.shared.domain.walk.LocationSample
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * GPSリプレイテストのフィクスチャ（architecture.md §7「実際の散歩の location_sample を
 * フィクスチャ化し、ロジック変更のたびに回帰させる」）。
 *
 * `session` はアプリのエクスポートJSON（`WalkSessionExporterImpl`）と**同じ形**にしてある。
 * 実散歩のログを追加するときは、エクスポートしたJSONをそのまま貼り付けられる。
 * スキーマと追加手順は README.md を参照。
 */
@Serializable
internal data class GpsReplayFixture(
    val schemaVersion: Int = SCHEMA_VERSION,
    /** フィクスチャの識別名。失敗時にどれが壊れたか分かるように使う。 */
    val name: String,
    /** 何を歩いたログなのかのメモ（実データなら日付や区間の説明）。 */
    val note: String = "",
    /** そのとき使ったwayマスタ。`geometry` はDB列と同じ `lat,lon;lat,lon;...` 形式。 */
    val ways: List<FixtureWay>,
    /** アプリのエクスポートJSONそのまま。 */
    val session: FixtureSession,
    /** 目視で確認した正解の通過列（時刻順）。 */
    val expectedPassages: List<FixturePassage>,
) {
    companion object {
        const val SCHEMA_VERSION: Int = 1
    }
}

@Serializable
internal data class FixtureWay(
    val id: Long,
    val name: String? = null,
    val highway: String,
    val geometry: String,
)

@Serializable
internal data class FixtureSession(
    @SerialName("sessionId") val sessionId: Long,
    val startedAt: Long,
    val endedAt: Long? = null,
    val endReason: String? = null,
    val samples: List<FixtureSample>,
)

@Serializable
internal data class FixtureSample(
    val ts: Long,
    val lat: Double,
    val lon: Double,
    val accuracy: Double,
)

@Serializable
internal data class FixturePassage(
    val wayId: Long,
    val ts: Long,
)

/** フィクスチャの読み込み。エクスポートJSONの余分なフィールドは無視する。 */
internal object GpsReplayFixtureLoader {

    // sampleCount など、テストが使わないフィールドが増えても読めるようにしておく
    private val json = Json { ignoreUnknownKeys = true }

    fun load(raw: String): GpsReplayFixture {
        val fixture = json.decodeFromString<GpsReplayFixture>(raw)
        check(fixture.schemaVersion == GpsReplayFixture.SCHEMA_VERSION) {
            "${fixture.name}: 未知のフィクスチャ形式 v${fixture.schemaVersion}"
        }
        return fixture
    }
}

/** マスタとして matcher に渡すway（長さは geometry から計算するので持たせない）。 */
internal fun GpsReplayFixture.toWays(): List<Way> = ways.map { way ->
    val geometry = WayGeometryCodec.decode(way.geometry)
    Way(
        id = way.id,
        name = way.name,
        highway = way.highway,
        geometry = geometry,
        lengthMeters = GeoDistance.pathLengthMeters(geometry),
    )
}

internal fun GpsReplayFixture.toSamples(): List<LocationSample> = session.samples.map { sample ->
    LocationSample(
        sessionId = session.sessionId,
        timestampMs = sample.ts,
        latitude = sample.lat,
        longitude = sample.lon,
        accuracyMeters = sample.accuracy,
    )
}

internal fun GpsReplayFixture.toExpectedPassages(): List<Passage> = expectedPassages.map {
    Passage(sessionId = session.sessionId, wayId = it.wayId, timestampMs = it.ts)
}

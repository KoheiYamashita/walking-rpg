package com.walkingrpg.app.ui.setup

import com.walkingrpg.shared.domain.osm.OsmArea
import com.walkingrpg.shared.domain.osm.OsmAreaSnapshot
import com.walkingrpg.shared.domain.osm.OsmAreaSource
import com.walkingrpg.shared.domain.osm.OsmMasterCounts
import com.walkingrpg.shared.domain.osm.OsmMasterRepository
import com.walkingrpg.shared.domain.osm.Poi
import com.walkingrpg.shared.domain.osm.Way
import com.walkingrpg.shared.domain.setup.HomeAnchor
import com.walkingrpg.shared.domain.setup.LlmConnectionSettings
import com.walkingrpg.shared.domain.setup.LlmConnectionTestResult
import com.walkingrpg.shared.domain.setup.LlmConnectionTester
import com.walkingrpg.shared.domain.setup.SetupRepository
import com.walkingrpg.shared.domain.setup.WeatherProviderChoice
import com.walkingrpg.shared.domain.setup.WeatherSettings
import com.walkingrpg.shared.domain.walk.CurrentLocationRepository
import com.walkingrpg.shared.domain.walk.LocationFix
import com.walkingrpg.shared.domain.walk.LocationPermissionRepository
import com.walkingrpg.shared.domain.walk.LocationPermissionStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * セットアップ（issue #6）と設定画面（issue #20）が共有するテスト用差し替え。
 *
 * 両画面はUseCaseを100%共有しているので、フェイクも1組でよい
 * （`ReviewFakes.kt` と同じ形で切り出してある）。
 * データ層の実装（SecureStorage・Overpass・SQLDelight）の正しさは shared 側の
 * テストが見ているので、ここでは「ViewModelが何をどの順で呼ぶか」だけを見られる
 * 最小の実装にする。
 */

/**
 * 応答を任意のタイミングで返せる疎通テスター。
 *
 * 「テスト中に入力が変わったら結果を捨てる」を再現するために、
 * [complete] を呼ぶまで `test` が返らない。
 */
internal class SuspendingLlmConnectionTester : LlmConnectionTester {
    private val response = CompletableDeferred<LlmConnectionTestResult>()

    /** 疎通テストに渡された設定（保存されたのがどれかを見るため）。 */
    var testedSettings: LlmConnectionSettings? = null
        private set

    fun complete(result: LlmConnectionTestResult) {
        response.complete(result)
    }

    override suspend fun test(settings: LlmConnectionSettings): LlmConnectionTestResult {
        testedSettings = settings
        return response.await()
    }
}

/** 即座に固定の結果を返す疎通テスター（待たせる必要がないテスト向け）。 */
internal class ImmediateLlmConnectionTester(
    private val result: LlmConnectionTestResult,
) : LlmConnectionTester {
    override suspend fun test(settings: LlmConnectionSettings): LlmConnectionTestResult = result
}

internal class FakeSetupRepository(
    private var llm: LlmConnectionSettings? = null,
    private var weather: WeatherSettings = WeatherSettings(WeatherProviderChoice.OPEN_METEO),
    private var home: HomeAnchor? = null,
) : SetupRepository {
    private var completed = false

    /** 保存された値の確認用（キーそのものは検証でも出さない）。 */
    val savedLlm: LlmConnectionSettings? get() = llm
    val savedWeather: WeatherSettings get() = weather
    val savedHome: HomeAnchor? get() = home

    override suspend fun loadLlmConnection(): LlmConnectionSettings? = llm

    override suspend fun saveLlmConnection(settings: LlmConnectionSettings) {
        llm = settings
    }

    override suspend fun loadWeatherSettings(): WeatherSettings = weather

    override suspend fun saveWeatherSettings(settings: WeatherSettings) {
        weather = settings
    }

    override suspend fun loadHomeAnchor(): HomeAnchor? = home

    override suspend fun saveHomeAnchor(anchor: HomeAnchor) {
        home = anchor
    }

    override suspend fun isSetupCompleted(): Boolean = completed

    override suspend fun markSetupCompleted() {
        completed = true
    }
}

internal class FakeOsmMasterRepository(
    private var counts: OsmMasterCounts,
) : OsmMasterRepository {
    override suspend fun save(ways: List<Way>, pois: List<Poi>) {
        counts = OsmMasterCounts(wayCount = ways.size, poiCount = pois.size)
    }

    override suspend fun counts(): OsmMasterCounts = counts

    override suspend fun ways(): List<Way> = emptyList()

    override suspend fun pois(): List<Poi> = emptyList()
}

internal class FakeOsmAreaSource : OsmAreaSource {
    override suspend fun fetchArea(area: OsmArea): OsmAreaSnapshot =
        OsmAreaSnapshot(ways = emptyList(), pois = emptyList())
}

/** 現在地。`null` は「権限がない・測位できない」。座標は架空（CONTRIBUTING.md）。 */
internal class FakeCurrentLocationRepository(
    private val fix: LocationFix? = LocationFix(
        timestampMs = 0,
        latitude = 12.0,
        longitude = 34.0,
        accuracyMeters = 5.0,
    ),
) : CurrentLocationRepository {
    override suspend fun currentFix(): LocationFix? = fix
}

internal class FakePermissionRepository(
    initial: LocationPermissionStatus = LocationPermissionStatus.GRANTED,
) : LocationPermissionRepository {
    override val status: StateFlow<LocationPermissionStatus> = MutableStateFlow(initial)

    /** `request()` が呼ばれた回数（権限が無いときに要求へ回すことの確認用）。 */
    var requestCount = 0
        private set

    override fun refresh() = Unit

    override fun request() {
        requestCount++
    }
}

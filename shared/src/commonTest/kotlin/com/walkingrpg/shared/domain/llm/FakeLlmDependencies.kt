package com.walkingrpg.shared.domain.llm

import com.walkingrpg.shared.domain.map.GeoPoint
import com.walkingrpg.shared.domain.osm.OsmMasterCounts
import com.walkingrpg.shared.domain.osm.OsmMasterRepository
import com.walkingrpg.shared.domain.osm.Poi
import com.walkingrpg.shared.domain.osm.PoiKind
import com.walkingrpg.shared.domain.osm.Way
import com.walkingrpg.shared.domain.setup.HomeAnchor
import com.walkingrpg.shared.domain.setup.LlmConnectionSettings
import com.walkingrpg.shared.domain.setup.LlmFormat
import com.walkingrpg.shared.domain.setup.SetupRepository
import com.walkingrpg.shared.domain.setup.WeatherSettings
import kotlinx.coroutines.yield

/**
 * LLM生成基盤のテスト用差し替え。
 *
 * HTTP実装（2フォーマット）は `data/llm` 側のテストが MockEngine で見ているので、
 * ここでは「UseCaseが何をどの順で読み書きするか」だけを見られる最小の形にする。
 *
 * APIキーは架空の文字列（実キーはコードにもテストにも書かない）。
 */

/** テスト用の接続設定。APIキーはダミー。 */
internal fun testLlmSettings(format: LlmFormat = LlmFormat.ANTHROPIC) = LlmConnectionSettings(
    format = format,
    baseUrl = "https://llm.example.invalid",
    model = "test-model",
    apiKey = "dummy-key-for-test",
)

/** 呼ばれた回数と依頼内容を覚えるクライアント。 */
internal class FakeLlmClient(
    override val format: LlmFormat = LlmFormat.ANTHROPIC,
    /** 返す本文（依頼の順に消費する。尽きたら最後の1つを繰り返す）。 */
    private val responses: List<String> = listOf("""{"flavor": "生成された一言。"}"""),
    /** 非nullなら毎回これを投げる（圏外・キー無効・レート制限の再現）。 */
    private val failure: LlmUnavailableException? = null,
    /**
     * 生成の途中で必ず1回中断する（通信は待たされるもの、の再現）。
     * 並行実行の検証で、他のコルーチンに割り込む隙を作るために使う。
     */
    private val suspendWhileGenerating: Boolean = false,
) : LlmClient {

    val requests = mutableListOf<LlmRequest>()
    val settings = mutableListOf<LlmConnectionSettings>()

    override suspend fun generate(
        request: LlmRequest,
        settings: LlmConnectionSettings,
    ): LlmResponse {
        requests += request
        this.settings += settings
        if (suspendWhileGenerating) yield()
        failure?.let { throw it }
        val index = (requests.size - 1).coerceAtMost(responses.lastIndex)
        return LlmResponse(responses[index])
    }
}

/** どのフォーマットでも同じ実装に落とす差し替え（振り分けの検証はフォーマットごとに1個ずつ渡す）。 */
internal class FakeLlmClientSelector(
    private val clients: Map<LlmFormat, LlmClient>,
) : LlmClientSelector {

    constructor(client: LlmClient) : this(LlmFormat.entries.associateWith { client })

    override fun client(format: LlmFormat): LlmClient = clients.getValue(format)
}

/** `llm_cache` のインメモリ版。行の有無＋プロンプト指紋の一致が「生成済み」の判定そのもの。 */
internal class FakeLlmCacheRepository(
    private val rows: MutableMap<String, LlmCacheEntry> = mutableMapOf(),
) : LlmCacheRepository {

    /** `save` の呼び出し履歴（置き換えが何回起きたかを見たいので列で持つ）。 */
    val saved = mutableListOf<LlmCacheEntry>()

    override suspend fun entry(cacheKey: String): LlmCacheEntry? = rows[cacheKey]

    override suspend fun entries(kind: LlmTaskKind): Map<String, LlmCacheEntry> =
        rows.filterValues { it.kind == kind }

    override suspend fun save(entry: LlmCacheEntry) {
        saved += entry
        rows[entry.cacheKey] = entry
    }

    fun rows(): Map<String, LlmCacheEntry> = rows.toMap()
}

/** 回線種別の差し替え。 */
internal class FakeNetworkStatus(private val unmetered: Boolean = true) : NetworkStatus {
    override fun isUnmetered(): Boolean = unmetered
}

/** POIマスタだけを持つ [OsmMasterRepository]。way は使わない前提で落とす。 */
internal class PoiOnlyOsmMasterRepository(
    var pois: List<Poi> = emptyList(),
) : OsmMasterRepository {

    override suspend fun pois(): List<Poi> = pois

    override suspend fun counts(): OsmMasterCounts =
        OsmMasterCounts(wayCount = 0, poiCount = pois.size)

    override suspend fun save(ways: List<Way>, pois: List<Poi>) = notUsed()
    override suspend fun ways(): List<Way> = notUsed()

    private fun notUsed(): Nothing = error("地点フレーバーの生成では使わない")
}

/** LLM接続設定だけを持つ [SetupRepository]。他は使わない前提で落とす。 */
internal class LlmSetupRepository(
    private val settings: LlmConnectionSettings? = testLlmSettings(),
) : SetupRepository {

    var loadCount = 0
        private set

    override suspend fun loadLlmConnection(): LlmConnectionSettings? {
        loadCount++
        return settings
    }

    override suspend fun saveLlmConnection(settings: LlmConnectionSettings) = notUsed()
    override suspend fun loadWeatherSettings(): WeatherSettings = notUsed()
    override suspend fun saveWeatherSettings(settings: WeatherSettings) = notUsed()
    override suspend fun loadHomeAnchor(): HomeAnchor? = notUsed()
    override suspend fun saveHomeAnchor(anchor: HomeAnchor) = notUsed()
    override suspend fun isSetupCompleted(): Boolean = notUsed()
    override suspend fun markSetupCompleted() = notUsed()

    private fun notUsed(): Nothing = error("地点フレーバーの生成では使わない")
}

/**
 * テスト用のPOI。座標は架空の値（実在の座標はテストに書かない。
 * CONTRIBUTING「位置情報の扱い」）。プロンプトには座標が乗らないので値は結果に影響しない。
 */
internal fun testPoi(
    id: String,
    kind: PoiKind = PoiKind.PARK,
    name: String? = null,
): Poi = Poi(
    id = id,
    kind = kind,
    tags = buildMap {
        put("leisure", "park")
        if (name != null) put("name", name)
    },
    location = GeoPoint(latitude = 12.3456, longitude = 34.5678),
)

/** 好きな結果を返すだけのキュー（ドレインの器の検証用）。 */
internal class FakeLlmGenerationQueue(
    override val taskKind: LlmTaskKind = LlmTaskKind.POI_FLAVOR,
    private val outcome: LlmGenerationOutcome = LlmGenerationOutcome(LlmTaskKind.POI_FLAVOR),
    /** 非nullなら投げる（キューの実装がバグで投げたときの保険の検証用）。 */
    private val failure: Throwable? = null,
    private val suspendWhileDraining: Boolean = false,
) : LlmGenerationQueue {

    var drainCount = 0
        private set

    /** 同時に [drain] の中に居たコルーチンの最大数（直列化の検証用）。 */
    var maxConcurrency = 0
        private set

    private var active = 0

    override suspend fun drain(): LlmGenerationOutcome {
        drainCount++
        active++
        maxConcurrency = maxOf(maxConcurrency, active)
        if (suspendWhileDraining) yield()
        active--
        failure?.let { throw it }
        return outcome
    }
}

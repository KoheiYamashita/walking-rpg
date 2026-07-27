package com.walkingrpg.shared.data.osm

import com.walkingrpg.shared.domain.osm.OsmArea
import com.walkingrpg.shared.domain.osm.OsmAreaSnapshot
import com.walkingrpg.shared.domain.osm.OsmAreaSource
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.parameters

/** Overpassとのやり取りが成立しなかったとき（HTTPエラー等）。 */
open class OverpassException(message: String) : Exception(message)

/**
 * Overpassが「途中までの結果」を返したとき（応答の `remark` 付き）。
 *
 * サーバ側タイムアウト・メモリ超過で起きる。HTTPは200なので、これを検出しないと
 * 欠けたデータで正常にマスタを作り直してしまう。混雑が原因なので時間を置けば通る。
 */
class OverpassPartialResponseException(remark: String) : OverpassException(
    "Overpassが混雑しています。しばらくしてから再試行してください。（$remark）",
)

/**
 * Overpass APIから対象圏を取得する [OsmAreaSource] 実装。
 *
 * way（`out geom`）とPOI（`out center`）で出力形式が違うので2リクエスト投げる。
 * タイムアウト・User-Agent・リトライは [osmHttpClient] 側（Ktorのプラグイン）に寄せてある。
 *
 * クエリはPOST（フォーム）で送る：GETだとURL長制限に引っかかる上に、
 * Overpass側のキャッシュ・ログにクエリ（＝対象圏の座標）が残りやすい。
 */
internal class OverpassOsmAreaSource(
    private val httpClient: HttpClient,
    private val config: OverpassConfig,
) : OsmAreaSource {

    override suspend fun fetchArea(area: OsmArea): OsmAreaSnapshot {
        val ways = OverpassResponseParser.parseWays(
            execute(OverpassQuery.ways(area, config.queryTimeoutSeconds)),
        )
        val pois = OverpassResponseParser.parsePois(
            execute(OverpassQuery.pois(area, config.queryTimeoutSeconds)),
        )
        return OsmAreaSnapshot(ways = ways, pois = pois)
    }

    private suspend fun execute(query: String): String {
        val response = httpClient.submitForm(
            url = config.baseUrl,
            formParameters = parameters { append("data", query) },
        )
        if (response.status != HttpStatusCode.OK) {
            throw OverpassException("Overpass API がエラーを返しました（HTTP ${response.status.value}）")
        }
        return response.bodyAsText()
    }
}

package com.walkingrpg.shared.domain.osm

/**
 * 対象圏のOSMデータの取得元（architecture.md §5「OSMデータ取り込み」）。
 *
 * ドメイン層はこのインターフェースだけを知る。Overpass APIのクエリ言語も
 * 応答JSONの形もこの向こう側（データ層）に閉じる。
 */
interface OsmAreaSource {
    /** 対象圏を1回ぶん取得する。通信・パースの失敗は例外で返す。 */
    suspend fun fetchArea(area: OsmArea): OsmAreaSnapshot
}

/** マスタ（`way` / `poi`）の永続化境界。 */
interface OsmMasterRepository {
    /**
     * 取り込み結果を保存する。1トランザクションで、OSMのIDをキーに置き換える
     * （再実行しても件数が増えない＝冪等）。
     */
    suspend fun save(ways: List<Way>, pois: List<Poi>)

    suspend fun counts(): OsmMasterCounts

    suspend fun ways(): List<Way>

    suspend fun pois(): List<Poi>
}

/** マスタの現在の件数。 */
data class OsmMasterCounts(
    val wayCount: Int,
    val poiCount: Int,
)

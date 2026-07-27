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
     * 取り込み結果でマスタを**作り直す**（既存を全削除してから挿入、1トランザクション）。
     *
     * 差分更新にしないのは、OSM側で廃止された地物や、あとから配置禁止タグが
     * 付いた地物を確実に消すため。マスタは真実の源ではないので作り直してよい。
     * 同じ入力からは必ず同じ状態になる（冪等）。
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

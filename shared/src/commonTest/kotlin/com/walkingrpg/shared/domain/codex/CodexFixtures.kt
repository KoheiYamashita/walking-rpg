package com.walkingrpg.shared.domain.codex

import com.walkingrpg.shared.domain.matching.SessionVisit
import com.walkingrpg.shared.domain.matching.SyntheticWalk
import com.walkingrpg.shared.domain.osm.Poi
import com.walkingrpg.shared.domain.osm.PoiKind

/**
 * 図鑑テストの合成データ。
 *
 * 座標は `SyntheticWalk` の架空原点からのメートルで組む（実在の座標はテストに書かない。
 * CONTRIBUTING「位置情報の扱い」）。距離の意図がコードから読めるようにするため、
 * 「北何m・東何m」で置く。
 */

/** 原点から北[northMeters]・東[eastMeters]のPOI。 */
internal fun codexPoi(
    id: String,
    kind: PoiKind,
    northMeters: Double = 0.0,
    eastMeters: Double = 0.0,
): Poi = Poi(
    id = id,
    kind = kind,
    tags = emptyMap(),
    location = SyntheticWalk.point(northMeters = northMeters, eastMeters = eastMeters),
)

/**
 * テスト用の種。閾値と棚だけが結果に効くので、名前と予兆文は固定。
 *
 * 本番のカタログ（[SpeciesCatalog]）を使うと閾値を変えた瞬間にテストが壊れるので、
 * 判定のテストは自前の種で組む（カタログそのものの妥当性は `SpeciesCatalogTest`）。
 */
internal fun testSpecies(
    id: String,
    category: CodexCategory,
    requiredVisitCount: Int,
): Species = Species(
    id = id,
    name = "テスト種$id",
    category = category,
    requiredVisitCount = requiredVisitCount,
    foreshadowText = "何かの気配がある。",
)

/** 「この道をこの散歩で通った」の並び。時刻は [SyntheticWalk.START_MS] からの分。 */
internal fun visits(vararg sessionIdToMinute: Pair<Long, Long>): List<SessionVisit> =
    sessionIdToMinute.map { (sessionId, minute) ->
        SessionVisit(
            sessionId = sessionId,
            firstTimestampMs = SyntheticWalk.START_MS + minute * 60_000L,
        )
    }

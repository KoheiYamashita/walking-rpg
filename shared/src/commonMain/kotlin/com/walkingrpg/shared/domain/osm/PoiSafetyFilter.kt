package com.walkingrpg.shared.domain.osm

/**
 * 配置可能／配置禁止の判定（design.md §6「安全設計」）。
 *
 * > **LLMに場所を選ばせない。** システム側が OSM タグで候補を絞り、
 * > その中からLLMに割り当てさせる。
 * > - 配置可能：公園、河川敷、神社、駅、公道、公共施設
 * > - 配置禁止：民家、学校、病院、私有地、線路、危険箇所
 *
 * ルールはこのファイル1箇所に集約する。取り込み時点で弾くので、
 * 下流（配置候補の抽選・LLMへの受け渡し）は「DBにあるPOIは全部安全」を前提にできる。
 *
 * 判定は**禁止が最優先**：分類できる地物でも禁止タグが1つでも付いていれば落とす
 * （例：公園の敷地に見えても `access=private` なら入れない）。
 */
object PoiSafetyFilter {

    /** POI1件の判定結果。 */
    sealed interface Verdict {
        /** 配置可能。図鑑カテゴリが決まっている。 */
        data class Accepted(val kind: PoiKind) : Verdict

        /** 配置禁止（design.md §6）。[reason] は禁止に当たったタグ。 */
        data class Rejected(val reason: String) : Verdict

        /** 禁止ではないが、どの図鑑カテゴリにも当てはまらないので取り込まない。 */
        data object Unclassified : Verdict
    }

    fun judge(tags: Map<String, String>): Verdict {
        forbiddenReason(tags)?.let { return Verdict.Rejected(it) }
        return classify(tags)?.let { Verdict.Accepted(it) } ?: Verdict.Unclassified
    }

    /**
     * 配置禁止に当たるならその理由（`key=value`）を返す。安全なら `null`。
     *
     * 「迷ったら禁止側に倒す」。取りこぼした素材はあとから足せるが、
     * 民家の庭先にシナリオを置いてしまうのは取り返しがつかない。
     */
    fun forbiddenReason(tags: Map<String, String>): String? {
        FORBIDDEN_BY_KEY_VALUE.forEach { (key, values) ->
            val value = tags[key] ?: return@forEach
            if (value in values) return "$key=$value"
        }
        FORBIDDEN_KEYS.forEach { key ->
            tags[key]?.let { return "$key=$it" }
        }
        // 私有地・立入禁止。`access=yes/permissive/customers` などは通す。
        tags["access"]?.let { access ->
            if (access in FORBIDDEN_ACCESS_VALUES) return "access=$access"
        }
        // 線路そのもの（踏切・駅は配置可能なので railway キー全体は禁止にしない）。
        tags["railway"]?.let { railway ->
            if (railway !in ALLOWED_RAILWAY_VALUES) return "railway=$railway"
        }
        // 建物は「住居系だけ」禁止。公共施設・商店の建物は下の分類で拾う。
        tags["building"]?.let { building ->
            if (building in FORBIDDEN_BUILDING_VALUES) return "building=$building"
        }
        return null
    }

    /** 図鑑カテゴリへの分類。素材にならない地物は `null`。 */
    fun classify(tags: Map<String, String>): PoiKind? {
        tags["natural"]?.let { natural ->
            if (natural == "tree") return PoiKind.TREE
            if (natural in WATER_NATURAL_VALUES) return PoiKind.WATER
        }
        tags["waterway"]?.let { waterway ->
            if (waterway in WATER_WATERWAY_VALUES) return PoiKind.WATER
        }
        tags["leisure"]?.let { leisure ->
            if (leisure in PARK_LEISURE_VALUES) return PoiKind.PARK
        }
        tags["landuse"]?.let { landuse ->
            if (landuse in FARMLAND_LANDUSE_VALUES) return PoiKind.FARMLAND
            if (landuse == "recreation_ground") return PoiKind.PARK
        }
        tags["railway"]?.let { railway ->
            if (railway in ALLOWED_RAILWAY_VALUES) return PoiKind.RAILWAY
        }
        tags["historic"]?.let { historic ->
            if (historic in SHRINE_HISTORIC_VALUES) return PoiKind.SHRINE
            if (historic in LANDMARK_HISTORIC_VALUES) return PoiKind.LANDMARK
        }
        tags["tourism"]?.let { tourism ->
            if (tourism in LANDMARK_TOURISM_VALUES) return PoiKind.LANDMARK
        }
        tags["amenity"]?.let { amenity ->
            if (amenity == "place_of_worship") return PoiKind.SHRINE
            if (amenity in PUBLIC_AMENITY_VALUES) return PoiKind.PUBLIC
            if (amenity in SHOP_AMENITY_VALUES) return PoiKind.SHOP
        }
        // 商店は種類が多すぎて列挙できないので shop キーの存在で拾う
        // （危険・私有地系は上の禁止判定で既に落ちている）。
        if (tags.containsKey("shop")) return PoiKind.SHOP
        if (tags["public_transport"] == "station") return PoiKind.RAILWAY
        return null
    }

    // --- 配置禁止（design.md §6）------------------------------------------------

    private val FORBIDDEN_BY_KEY_VALUE: Map<String, Set<String>> = mapOf(
        "amenity" to setOf(
            // 学校
            "school", "kindergarten", "college", "university", "childcare", "music_school",
            "driving_school", "language_school", "prep_school",
            // 病院
            "hospital", "clinic", "doctors", "dentist", "nursing_home", "social_facility",
            "veterinary",
            // 危険箇所・立ち入るべきでない場所
            "fuel", "waste_transfer_station", "prison",
        ),
        "landuse" to setOf(
            // 民家の敷地・私有地・危険箇所
            "residential", "industrial", "military", "landfill", "quarry", "construction",
            "garages", "cemetery",
        ),
        "highway" to setOf("construction", "raceway"),
        "natural" to setOf("cliff", "sinkhole"),
        "waterway" to setOf("weir", "dam", "sluice_gate", "lock_gate"),
        "man_made" to setOf("adit", "mineshaft", "petroleum_well"),
    )

    /** キーが存在するだけで禁止。 */
    private val FORBIDDEN_KEYS: Set<String> = setOf(
        "healthcare",
        "hazard",
        "military",
        "power",
    )

    private val FORBIDDEN_ACCESS_VALUES: Set<String> = setOf("private", "no", "permit")

    private val FORBIDDEN_BUILDING_VALUES: Set<String> = setOf(
        "house", "residential", "apartments", "detached", "semidetached_house", "terrace",
        "bungalow", "dormitory", "hut", "school", "hospital", "farm", "garage", "garages",
    )

    /** 踏切・駅は「関門」として配置可能（design.md §9監査）。線路本体は禁止。 */
    private val ALLOWED_RAILWAY_VALUES: Set<String> = setOf(
        "level_crossing",
        "crossing",
        "station",
        "halt",
        "tram_stop",
    )

    // --- 図鑑カテゴリ（design.md §8・§9の4本柱＋α）-------------------------------

    private val PARK_LEISURE_VALUES = setOf("park", "garden", "playground", "nature_reserve")
    private val FARMLAND_LANDUSE_VALUES = setOf("farmland", "orchard", "allotments", "meadow")
    private val WATER_NATURAL_VALUES = setOf("water", "spring", "wetland", "beach")
    private val WATER_WATERWAY_VALUES = setOf("stream", "river", "canal", "waterfall")
    private val SHRINE_HISTORIC_VALUES = setOf("wayside_shrine", "wayside_cross")
    private val LANDMARK_HISTORIC_VALUES = setOf("monument", "memorial", "archaeological_site", "ruins")
    private val LANDMARK_TOURISM_VALUES = setOf("artwork", "viewpoint", "attraction", "picnic_site")
    private val PUBLIC_AMENITY_VALUES = setOf(
        "library", "police", "post_office", "community_centre", "townhall", "fire_station",
        "toilets", "drinking_water", "bench", "fountain", "public_bath", "theatre", "cinema",
    )
    private val SHOP_AMENITY_VALUES = setOf(
        "cafe", "restaurant", "fast_food", "bakery", "ice_cream", "marketplace", "vending_machine",
    )
}

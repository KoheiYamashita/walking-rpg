package com.walkingrpg.shared.domain.snapshot

import com.walkingrpg.shared.domain.growth.GrowthStage
import com.walkingrpg.shared.domain.map.GeoPoint

/**
 * 月次スナップショットのドメインモデル（design.md §4.5・architecture.md §4）。
 *
 * 純Kotlin。描画APIもSQLDelightの行型もJSONの形もここには現れない
 * （画像のエンコードはUI層、JSONへの変換はデータ層の仕事）。
 */

/**
 * 端末ローカル暦の月（'YYYY-MM'）。
 *
 * `CalendarDay` と同じ扱いで、時刻（epoch millis）ではなくタイムゾーンを剥がした暦で持つ。
 * 「その人にとっての6月」が月の境界であって、UTCの6月ではない。
 * 暦の計算（前月・月初〜翌月初の範囲）はドメインではやらず
 * [com.walkingrpg.shared.domain.steps.CalendarDays] に預ける。
 *
 * 文字列のまま比較してよい（ゼロ埋めの 'YYYY-MM' は辞書順＝時系列順）。
 * [Comparable] を実装してあるのは、生成対象の月を「古い月から先月まで」で
 * 挟み込むときに `>=` で書けるようにするため。
 */
data class CalendarMonth(val iso: String) : Comparable<CalendarMonth> {
    init {
        require(ISO_PATTERN.matches(iso)) { "月は 'YYYY-MM' 形式で指定する: $iso" }
    }

    override fun compareTo(other: CalendarMonth): Int = iso.compareTo(other.iso)

    override fun toString(): String = iso

    companion object {
        private val ISO_PATTERN = Regex("""\d{4}-(0[1-9]|1[0-2])""")
    }
}

/**
 * 月の epoch millis 範囲（月初 0:00 〜 翌月初 0:00）。
 *
 * 上端は**含まない**（`[fromMs, untilMs)`）。含めると月末23:59:59.999 に始まった散歩と
 * 翌月1日0:00 に始まった散歩の両方が同じ月に入る隙ができる。
 */
data class MonthRange(val fromMs: Long, val untilMs: Long) {
    init {
        require(fromMs < untilMs) { "月の範囲は fromMs < untilMs: $fromMs..$untilMs" }
    }

    /** [epochMillis] がこの月に入るか（上端は含まない）。 */
    operator fun contains(epochMillis: Long): Boolean = epochMillis in fromMs until untilMs
}

/**
 * 1ヶ月ぶんの数値（`snapshot.stats_json` の中身）。
 *
 * design.md §4.5 の「パートナーが語る形式（Wrapped 的な体験）」の材料。
 * **語り（文章）はここには入れない**：文章はLLM生成で作り直せる（`llm_cache`）が、
 * 数値は「その月に何が起きたか」の記録そのもので、あとから passage を消しても
 * 遡って作り直せてはいけない。だから語りではなく数値を焼き込む。
 *
 * @param distanceMeters その月の散歩の距離の合計。1回ぶんの物差しは振り返りと同じ
 *  （`WalkReviewCalculator.distanceMeters`）＝月の合計は振り返りの足し算と必ず一致する。
 * @param newWayCount その月に**はじめて通った**道の本数（初回通過が当月に入る道）。
 * @param discoveredSpeciesNames その月に発見した種の名前（`SpeciesCatalog` の並び）。
 * @param sessionCount その月に始めた散歩の回数（月の帰属は `started_at` 基準）。
 */
data class MonthlySnapshotStats(
    val distanceMeters: Double,
    val newWayCount: Int,
    val discoveredSpeciesNames: List<String>,
    val sessionCount: Int,
) {
    init {
        require(distanceMeters >= 0.0) { "distanceMeters は0以上" }
        require(newWayCount >= 0) { "newWayCount は0以上" }
        require(sessionCount >= 0) { "sessionCount は0以上" }
    }
}

/**
 * 絵に描く範囲（緯度経度の外接矩形）。
 *
 * 抽象レイヤーだけを描く（背景タイルを焼かない）ので、カメラの中心とズームではなく
 * 「全部の道が入る箱」で持つ。1本しか道が無い月でも潰れないよう、
 * 箱の作成側（[SnapshotProjection.bounds]）が最小の広さを保証する。
 */
data class SnapshotBounds(
    val minLatitude: Double,
    val minLongitude: Double,
    val maxLatitude: Double,
    val maxLongitude: Double,
) {
    init {
        require(minLatitude <= maxLatitude) { "緯度は min <= max" }
        require(minLongitude <= maxLongitude) { "経度は min <= max" }
    }

    val centerLatitude: Double get() = (minLatitude + maxLatitude) / 2.0
    val centerLongitude: Double get() = (minLongitude + maxLongitude) / 2.0
}

/**
 * 絵に描く道1本（形＋育ちの段階）。
 *
 * 地図画面の `WayHighlight` と対になる型で、違いは**時点の強調を持たないこと**：
 * `isNewlyGrown`（直近の散歩で育った道）は「今この瞬間」の余韻で、
 * 永久保存する1枚には意味がない（開くたびに正しさが変わる情報は焼けない）。
 * 色を決めるのはUI層（ドメインは色を知らない）という分担も `WayHighlight` と同じ。
 */
data class SnapshotWay(
    val shape: List<GeoPoint>,
    val stage: GrowthStage,
)

/**
 * 1枚ぶんの描画材料（design.md §8「実地図の上に抽象レイヤー」の抽象レイヤーだけ）。
 *
 * ## 背景タイルを焼かない
 * 地図画面はOpenFreeMapからタイルをオンライン取得する（architecture.md §1）が、
 * 永久保存する1枚に他所から借りた絵を貼るわけにはいかない（オンライン取得が前提の絵は
 * 「永久」を名乗れない）。焼くのは手元のデータだけで組める抽象レイヤーで、
 * これは design.md §8 の「育ちの実感は色と振り返りで作る」そのもの。
 *
 * ## 「その月」の絵ではなく「その月までに育った街」の絵
 * [ways] は生成時点の `way_growth` 全件（＝その月までに歩いた道の累積）で、
 * 当月ぶんだけを切り出したりはしない。減衰なし設計（design.md §2）では街は
 * 積み上がるだけなので、月ごとの差分よりも「この月の終わりに街がどこまで育っていたか」が
 * アルバムとして意味を持つ。だから画像は生成時点で確定させ、あとから作り直さない
 * （`Snapshot.sq` のコメント）。
 *
 * @param bounds 全ての [ways] が入る箱。道が1本も無ければ `null`（描くものが無い月）。
 */
data class SnapshotScene(
    val month: CalendarMonth,
    val bounds: SnapshotBounds?,
    val ways: List<SnapshotWay>,
)

/**
 * 保存済みの1枚（architecture.md §4 `snapshot(month, image_path, stats_json, created_at)`）。
 *
 * @param imagePath 永続領域からの**相対パス**。絶対パスを持たない理由は `Snapshot.sq` のコメント。
 * @param createdAtMs この行を書いた時刻。月の時刻ではない（同じ月を後から穴埋め生成しても
 *  月の並びは [month] で決まる）。
 */
data class Snapshot(
    val month: CalendarMonth,
    val imagePath: String,
    val stats: MonthlySnapshotStats,
    val createdAtMs: Long,
) {
    init {
        require(imagePath.isNotBlank()) { "imagePath は空にしない" }
    }
}

/** スナップショット画像の置き場（永続領域からの相対ディレクトリ）。 */
const val SNAPSHOT_IMAGE_DIRECTORY: String = "snapshots"

/**
 * その月の画像の相対パス（'snapshots/2026-06.png'）。
 *
 * 月から機械的に決まるので、DBの行が無くてもファイルの在り処が分かる
 * ＝画像を書いたあとに行の挿入で落ちても、次の生成が同じ場所を指して書き直せる。
 */
fun CalendarMonth.snapshotImagePath(): String = "$SNAPSHOT_IMAGE_DIRECTORY/$iso.png"

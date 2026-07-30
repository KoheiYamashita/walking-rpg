package com.walkingrpg.shared.domain.review

import com.walkingrpg.shared.domain.codex.Species
import com.walkingrpg.shared.domain.growth.GrowthStage
import com.walkingrpg.shared.domain.growth.WayStageRaise
import com.walkingrpg.shared.domain.weather.SessionWeather

/**
 * 振り返り（design.md §3 の 17:08「帰宅」・§4.5、architecture.md §5「帰宅後」）のドメインモデル。
 *
 * 純Kotlin。SQLDelightの行型もLLMもここには現れない。
 *
 * ## 数値は即時、文章は遅延
 * architecture.md §5 の原則をそのまま型に落としてある：**このモデルに文章は入らない**。
 * 距離・時間・育った道・図鑑の進捗はすべてローカルの確定データから引けるので、
 * 圏外でも [WalkReview] だけで振り返りは成立する。パートナーの一言は
 * `llm_cache` を購読して後から差し込む（`ObserveWalkReviewRemarkUseCase`）ので、
 * 生成が間に合わなくても画面は完成した状態で出る。
 */

/**
 * 1回の散歩ぶんの振り返り。
 *
 * ## 見込みではなく確定データから組む
 * 歩行中のイベント（`WalkEventBus`）は読まない。あちらはプロセス内メモリの**見込み**
 * （`LiveGrowthEstimator`）で、確定するのは帰宅後の再計算だから
 * （`WalkEvent.GrowthStageUp` のKDoc）。ここは `passage` からの再計算で出すので、
 * プロセスを再起動しても過去のセッションを開き直しても**同じ振り返りが出る**（冪等）。
 *
 * @param distanceMeters 「歩いた距離」＝そのセッションの軌跡から出した**実移動距離**
 *  （`WalkDistanceCalculator`）。通ったwayの長さの合算ではない：あちらは少しでも
 *  触れた道を全長ぶん数えるので、実測で2倍近くに膨らんだ（design.md §11）。
 *  `walk_session` に距離列は持たず、毎回 `location_sample` から導く
 *  （architecture.md §4「導出テーブルはすべて再計算できる」と同じ考え方）。
 * @param weather 後付け取得（`SessionWeatherRepository`）が済んでいれば天候。
 *  **まだ取れていなければ `null`**＝画面はその行を出さない（数値サマリは残りだけで成立する）。
 * @param grownWays そのセッションの通過によって段階が上がった道（way ID順）。
 * @param discoveredSpecies そのセッションで新しく発見された種。
 * @param approachedSpecies そのセッションで予兆が立った種（まだ未発見）。
 *  出すのは予兆の文（[Species.foreshadowText]）だけで、**残り回数は出さない**
 *  （design.md §4.4）。
 * @param codexDiscoveredCount 図鑑の発見済み件数（`GetCodexUseCase` と同じ数え方）。
 * @param codexTotalCount 図鑑の総数。進捗は `n/17` の形でだけ出す
 *  ＝訪問回数・残り回数の数字は出さない（design.md §4.4）。
 */
data class WalkReview(
    val sessionId: Long,
    val startedAtMs: Long,
    val endedAtMs: Long?,
    val durationMs: Long,
    val distanceMeters: Double,
    val weather: SessionWeather? = null,
    val grownWays: List<ReviewGrownWay> = emptyList(),
    val discoveredSpecies: List<Species> = emptyList(),
    val approachedSpecies: List<Species> = emptyList(),
    val codexDiscoveredCount: Int = 0,
    val codexTotalCount: Int = 0,
) {
    /** 距離をkmで（表示・プロンプトの両方がこの1本を通る）。 */
    val distanceKm: Double get() = distanceMeters / 1_000.0
}

/**
 * 振り返りに出す「育った道」1本。
 *
 * [WayStageRaise] に道の名前を添えただけの型。段階の差分そのものは `way_growth` の
 * 再計算から出る（`GrowthDiff.stageRaises`）が、名前はマスタ（`way.name`）側の情報なので、
 * 差分の型に混ぜずここで合流させる。
 *
 * @param name OSMの `name` タグ。大半の生活道路は無名なので `null` が普通
 *  ＝表示側で「名もない道」等に言い換える（UI層の仕事）。
 */
data class ReviewGrownWay(
    val wayId: Long,
    val name: String?,
    val from: GrowthStage?,
    val to: GrowthStage,
) {
    companion object {
        fun of(raise: WayStageRaise, name: String?): ReviewGrownWay = ReviewGrownWay(
            wayId = raise.wayId,
            name = name,
            from = raise.from,
            to = raise.to,
        )
    }
}

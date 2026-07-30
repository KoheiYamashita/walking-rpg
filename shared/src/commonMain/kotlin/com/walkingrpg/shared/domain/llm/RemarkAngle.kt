package com.walkingrpg.shared.domain.llm

import com.walkingrpg.shared.domain.review.WalkReview

/**
 * パートナーの一言の「切り口」と、その選び方（issue #16・design.md §5「記憶を持つ」）。
 *
 * > 発話履歴を場所ごとに記録し、**同じ切り口の再使用を禁止**する
 *
 * 単調化対策の本体。同じ500m圏を毎日歩く相手に対して、材料（距離・育ち・発見・天候）は
 * 数週間で似通るので、**材料を増やすのではなく「今日はどこから話すか」を回す**。
 *
 * ## 禁止リストではなく1値を渡す
 * プロンプトには「今日はこの切り口を中心に書く」と**1つだけ**指示する
 * （「これは使うな」を並べない）。理由は2つ：
 * - 出力が安定する（禁止の列挙はモデルに無視されやすく、否定形は文体にも滲む）
 * - プロンプト指紋が安定する（禁止リストは履歴が増えるたびに伸びる＝
 *   同じ散歩でも指紋が変わり、`llm_cache` が当たらなくなる）
 */

/**
 * 一言の切り口。**enumの並び順が優先順位**（上が優先）。
 *
 * name は `utterance_log.angle` に文字列で入るので、**値の名前は変えないこと**
 * （[LlmTaskKind] と同じ理由。改名すると過去の履歴が別の切り口として見え、
 * 「もう言った」が効かなくなる）。
 *
 * 並びの根拠：
 * - 発見・予兆はその日の**出来事**で、話さないと不自然（design.md §4.4「発見は嬉しさの頂点」）
 * - 記憶はこの機能の主題（design.md §5）なので、日常の材料より前に置く
 * - 歩数（押し忘れ）は「なかったことにしない」ための言及なので、天候・時間帯より前
 * - 時間帯・距離は**必ず材料がある**ので最後に置く＝候補が空にならない
 *   （切り口が枯れてパートナーが無言になることはない）
 */
enum class RemarkAngle {
    /** 今日はじめて図鑑に載ったもの。 */
    DISCOVERY,

    /** 予兆の手がかり（何のものかは伏せたまま）。 */
    FORESHADOW,

    /** この道の記憶（design.md §5「ここ、初めて来た日は何もなかったね」）。 */
    MEMORY,

    /** 道の育ち（段階が上がった）。 */
    GROWTH,

    /** 歩数だけ残っている日への言及（design.md §3「開始を押し忘れた日」）。 */
    STEPS,

    /** 天候。 */
    WEATHER,

    /** 時間帯（朝・日中・夕方・夜）。 */
    TIME_OF_DAY,

    /** 歩いた距離・時間の様子。 */
    DISTANCE,
}

/**
 * 今日の材料と発話履歴から切り口を1つ決める**純関数**（乱数・時刻を使わない）。
 *
 * 同じ入力からは必ず同じ切り口が出ることが要：読み側（[ObserveWalkReviewRemarkUseCase]）は
 * 同じ材料からプロンプト指紋を計算し直して `llm_cache` と突き合わせるので、
 * ここが揺れると生成済みの一言が毎回「未生成」に見える
 * （＝定型文＋再生成の課金ループ。[GetWalkRemarkContextUseCase] のKDoc）。
 */
object RemarkAngleSelector {

    /**
     * その散歩で**材料がある**切り口。
     *
     * [RemarkAngle.TIME_OF_DAY] と [RemarkAngle.DISTANCE] は常に入る
     * （時刻と距離は必ず確定している）ので、戻り値が空になることはない。
     */
    fun availableAngles(
        review: WalkReview,
        memory: WalkMemory? = null,
        stepsMention: StepsMention? = null,
    ): Set<RemarkAngle> = buildSet {
        if (review.discoveredSpecies.isNotEmpty()) add(RemarkAngle.DISCOVERY)
        if (review.approachedSpecies.isNotEmpty()) add(RemarkAngle.FORESHADOW)
        if (memory != null) add(RemarkAngle.MEMORY)
        if (review.grownWays.isNotEmpty()) add(RemarkAngle.GROWTH)
        if (stepsMention != null) add(RemarkAngle.STEPS)
        // 天候不明で確定した行は「取れていない」と同じ扱い（WalkReviewRemarkFacts.of と同じ判定）。
        if (review.weather?.isKnown == true) add(RemarkAngle.WEATHER)
        add(RemarkAngle.TIME_OF_DAY)
        add(RemarkAngle.DISTANCE)
    }

    /**
     * 材料のある切り口のうち、その場所でまだ使っていないものを優先順（enumの並び）で1つ選ぶ。
     *
     * **枯渇しても無言にしない**：全部使用済みなら、いちばん古く使った切り口を再利用する。
     * 「もう全部言ったから何も言わない」は design.md §5 の「責めない。ただ喜ぶ」を
     * 黙って裏切る（毎日歩いている人にだけ沈黙が返る）。
     *
     * @param available [availableAngles] の結果。
     * @param lastUsedAtMs その場所で使った切り口 → 最後に使った時刻（`utterance_log.said_at`）。
     *  **このセッションより前のぶんだけ**を渡すこと（自分自身を含めると、
     *  一度生成したあとで指紋が変わる）。
     */
    fun select(
        available: Set<RemarkAngle>,
        lastUsedAtMs: Map<RemarkAngle, Long> = emptyMap(),
    ): RemarkAngle {
        // 常に材料がある2つ（時間帯・距離）が入っているはずだが、
        // 呼び出し側の作り方に依存させない（無言にしないことが最優先）。
        val candidates = RemarkAngle.entries.filter { it in available }
            .ifEmpty { listOf(RemarkAngle.DISTANCE) }

        return candidates.firstOrNull { it !in lastUsedAtMs }
            ?: candidates.minWith(
                compareBy({ lastUsedAtMs.getValue(it) }, { it.ordinal }),
            )
    }
}

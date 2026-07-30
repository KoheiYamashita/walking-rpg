package com.walkingrpg.shared.domain.llm

import com.walkingrpg.shared.domain.review.TimeOfDayResolver
import com.walkingrpg.shared.domain.review.WalkReview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 振り返りの一言を購読する（**通信しない**）。
 *
 * `GetPoiFlavorUseCase` / `GetSpeciesDescriptionUseCase` の「引くだけ」に対して、
 * こちらは**購読**なのが違い。振り返りは「開いてから生成が届く」画面なので、
 * 読み切りだと帰宅直後に必ず定型文で終わる（生成が終わるのは開いた数秒後）。
 * `llm_cache` の1行を購読しておけば、保存された瞬間に文章が差し替わる
 * （architecture.md §5「数値は即時、文章は遅延OK」）。
 *
 * プロンプトが変わっている（[LlmCacheEntry.matches] が false）行は「無い」扱いにする
 * ＝古い方針で書かれた一言を出し続けるより定型文のほうが安全で、次のドレイン
 * （[GenerateWalkReviewRemarkUseCase]）が作り直す（`GetPoiFlavorUseCase` と同じ規約）。
 */
class ObserveWalkReviewRemarkUseCase(
    private val cacheRepository: LlmCacheRepository,
    private val timeOfDayResolver: TimeOfDayResolver,
    private val config: LlmGenerationConfig = LlmGenerationConfig.DEFAULT,
) {
    /**
     * @param review 振り返りの数値サマリ。定型文の出し分けと、プロンプト指紋の計算に使う
     *  （生成側と同じ事実から同じ指紋を出さないとキャッシュが当たらない）。
     * @return 未生成のあいだは定型文、生成できたら生成文が流れる。必ず1回目が流れる
     *  ＝画面は文章の枠を空にしない。
     */
    operator fun invoke(review: WalkReview): Flow<WalkReviewRemark> {
        val facts = WalkReviewRemarkFacts.of(
            review = review,
            timeOfDay = timeOfDayResolver.timeOfDay(review.startedAtMs),
        )
        val promptHash = WalkReviewRemarkPromptBuilder
            .request(facts, config.walkReviewRemarkMaxTokens)
            .promptHash()

        return cacheRepository.observe(remarkCacheKey(review.sessionId)).map { entry ->
            val generated = entry?.takeIf { it.matches(promptHash) }
            if (generated == null) {
                WalkReviewRemark(text = WalkReviewRemarkFallback.text(facts), isGenerated = false)
            } else {
                WalkReviewRemark(text = generated.text, isGenerated = true)
            }
        }
    }
}

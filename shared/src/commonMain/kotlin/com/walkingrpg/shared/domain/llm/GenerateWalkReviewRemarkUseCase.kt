package com.walkingrpg.shared.domain.llm

import com.walkingrpg.shared.domain.Clock
import com.walkingrpg.shared.domain.review.GetWalkReviewUseCase
import com.walkingrpg.shared.domain.review.TimeOfDayResolver
import com.walkingrpg.shared.domain.review.WalkReview
import com.walkingrpg.shared.domain.setup.SetupRepository
import com.walkingrpg.shared.domain.walk.SessionEndReason
import com.walkingrpg.shared.domain.walk.WalkSessionRepository
import kotlinx.coroutines.CancellationException

/**
 * 振り返りのパートナーの一言を作る（design.md §5「帰宅後に語る＝LLMの主戦場」）。
 *
 * ```
 * 直近の終了済みセッション（数件）
 *   → キャッシュに無い／プロンプトが変わったセッションだけを選ぶ
 *   → 1件ずつ生成して llm_cache に保存
 * ```
 *
 * 3本目の [LlmGenerationQueue]。事前バッチ2本（地点フレーバー・図鑑の記述文）とは
 * 性格が2つ違う：
 *
 * ## 事前には作れない
 * 材料が「その散歩で何が起きたか」（[WalkReview]）なので、歩き終わるまで存在しない。
 * だから**帰宅直後に投げる**。生成が間に合わなくても振り返りは数値サマリだけで成立し、
 * 一言は定型文（[WalkReviewRemarkFallback]）が出る
 * （architecture.md §5「数値は即時、文章は遅延OK」）。
 *
 * ## 従量回線でも投げる
 * 事前バッチは数百件なのでWi-Fiを待つが、こちらは**1回の散歩につき1リクエスト**で、
 * それが帰宅直後の体験そのものに効く。テザリングしかない日に「今日の一言が出ない」のは
 * 通信量を惜しむ理由として釣り合わない（既定は
 * [LlmGenerationConfig.requireUnmeteredNetworkForWalkReview] = false）。
 *
 * ## 対象は直近だけ
 * 過去全件には作らない（[LlmGenerationConfig.walkReviewSessionLimit]）。
 * 放置で畳まれたセッション（[SessionEndReason.ABANDONED]）も除く：ユーザーが
 * 終えたつもりのない記録で、振り返りとして開く前提のものではない。
 */
class GenerateWalkReviewRemarkUseCase(
    private val walkSessionRepository: WalkSessionRepository,
    private val getWalkReview: GetWalkReviewUseCase,
    private val cacheRepository: LlmCacheRepository,
    private val setupRepository: SetupRepository,
    private val clients: LlmClientSelector,
    private val networkStatus: NetworkStatus,
    private val timeOfDayResolver: TimeOfDayResolver,
    private val clock: Clock,
    private val config: LlmGenerationConfig = LlmGenerationConfig.DEFAULT,
) : LlmGenerationQueue {

    override val taskKind: LlmTaskKind = LlmTaskKind.WALK_REVIEW_REMARK

    override suspend fun drain(): LlmGenerationOutcome {
        if (config.requireUnmeteredNetworkForWalkReview && !networkStatus.isUnmetered()) {
            return skipped(LlmSkipReason.METERED_NETWORK)
        }

        val sessions = walkSessionRepository
            .recentFinishedSessions(config.walkReviewSessionLimit)
            .filter { it.endReason != SessionEndReason.ABANDONED }
        if (sessions.isEmpty()) return skipped(LlmSkipReason.NOTHING_TO_GENERATE)

        // 未生成の選別まではローカル計算だけで済む（振り返りの組み立ても通信しない）。
        // プロンプト指紋がキャッシュ判定そのものなので、依頼はここで作ってしまう。
        var cached = 0
        val pending = mutableListOf<PendingRemark>()
        for (session in sessions) {
            // セッションはあるのに振り返りが組めない＝DBを入れ替えた等。数えずに飛ばす。
            val review = getWalkReview(session.id) ?: continue
            val request = requestFor(review)
            val entry = cacheRepository.entry(remarkCacheKey(session.id))
            if (entry != null && entry.matches(request.promptHash())) {
                cached++
            } else {
                pending += PendingRemark(sessionId = session.id, request = request)
            }
        }
        if (pending.isEmpty()) {
            return if (cached > 0) {
                LlmGenerationOutcome(taskKind = taskKind, cached = cached)
            } else {
                skipped(LlmSkipReason.NOTHING_TO_GENERATE)
            }
        }

        // 秘密（APIキー）を読むのは、生成する相手が居ると分かってから。
        val settings = setupRepository.loadLlmConnection()
            ?: return LlmGenerationOutcome(
                taskKind = taskKind,
                cached = cached,
                skipReason = LlmSkipReason.NOT_CONFIGURED,
            )
        val client = clients.client(settings.format)

        var generated = 0
        var failed = 0
        var remaining = 0

        for (one in pending) {
            if (generated >= config.maxGenerationsPerRun || failed >= config.maxFailuresPerRun) {
                remaining++
                continue
            }

            val response = try {
                client.generate(one.request, settings)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: LlmUnavailableException) {
                failed++
                // 設定・通信の問題は次のセッションでも同じ結果になる。残りは投げずに見送る。
                if (error.reason.stopsBatch()) {
                    remaining += pending.size - (generated + failed + remaining)
                    break
                }
                continue
            }

            // 形が崩れた応答は保存しない（定型文で凌いで次回リトライ）。
            val remark = WalkReviewRemarkResponseParser.parse(response.text)
            if (remark == null) {
                failed++
                continue
            }

            cacheRepository.save(
                LlmCacheEntry(
                    cacheKey = remarkCacheKey(one.sessionId),
                    kind = LlmTaskKind.WALK_REVIEW_REMARK,
                    promptHash = one.request.promptHash(),
                    text = remark,
                    createdAtMs = clock.nowMillis(),
                ),
            )
            generated++
        }

        return LlmGenerationOutcome(
            taskKind = taskKind,
            generated = generated,
            cached = cached,
            failed = failed,
            remaining = remaining,
        )
    }

    private fun requestFor(review: WalkReview): LlmRequest = WalkReviewRemarkPromptBuilder.request(
        facts = WalkReviewRemarkFacts.of(
            review = review,
            timeOfDay = timeOfDayResolver.timeOfDay(review.startedAtMs),
        ),
        maxTokens = config.walkReviewRemarkMaxTokens,
    )

    private fun skipped(reason: LlmSkipReason) =
        LlmGenerationOutcome(taskKind = taskKind, skipReason = reason)

    /** 生成待ちの1件（依頼はキャッシュ判定のときに作ってあるので使い回す）。 */
    private data class PendingRemark(
        val sessionId: Long,
        val request: LlmRequest,
    )
}

/** 振り返りの一言のキャッシュキー。生成側と読み出し側（[ObserveWalkReviewRemarkUseCase]）で共有する。 */
internal fun remarkCacheKey(sessionId: Long): String =
    llmCacheKey(LlmTaskKind.WALK_REVIEW_REMARK, walkReviewLogicalKey(sessionId))

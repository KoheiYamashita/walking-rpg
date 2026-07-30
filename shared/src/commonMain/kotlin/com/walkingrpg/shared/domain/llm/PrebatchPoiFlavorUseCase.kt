package com.walkingrpg.shared.domain.llm

import com.walkingrpg.shared.domain.Clock
import com.walkingrpg.shared.domain.osm.OsmMasterRepository
import com.walkingrpg.shared.domain.osm.Poi
import com.walkingrpg.shared.domain.setup.SetupRepository
import kotlinx.coroutines.CancellationException

/**
 * 対象圏の地点フレーバーを事前に作っておく
 * （design.md §7「地点の基本フレーバーはWi-Fi時の事前バッチ」・architecture.md §5）。
 *
 * ```
 * POIマスタ（対象圏の全地点）
 *   → キャッシュに無い／プロンプトが変わった地点だけを選ぶ
 *   → 上限まで生成して llm_cache に保存
 * ```
 *
 * ## なぜ事前に作るのか
 * **路上でLLMを待たせたら負け**（design.md §7）。散歩中に地点へ着いてから生成すると、
 * 圏外・レート制限・単純な遅さがそのまま体験の穴になる。家の中（Wi-Fi）で先に作って
 * おけば、散歩中の表示はDBを引くだけで済む。作れていない地点は定型文
 * （[PoiFlavorFallback]）で成立するので、**全件揃っていなくても散歩は始められる**。
 *
 * ## 従量課金の回線では走らせない
 * 対象圏のPOIは数百件（design.md §7）。テザリング中に一斉に投げると通信量と課金が
 * 一気に増える。[NetworkStatus] が「従量でない」と言わない限り見送る
 * （判定できないときも見送る）。見送っても冪等なので、次の起動で続きから埋まる。
 *
 * ## 失敗の扱い
 * 生成できなかった地点は**保存しない**＝次回のドレインでまた未生成として拾われる
 * （天候の「行を作らない」と同じ）。打ち切りの方針は
 * [LlmGenerationConfig.DEFAULT_MAX_FAILURES_PER_RUN] のKDoc。
 */
class PrebatchPoiFlavorUseCase(
    private val osmMasterRepository: OsmMasterRepository,
    private val cacheRepository: LlmCacheRepository,
    private val setupRepository: SetupRepository,
    private val clients: LlmClientSelector,
    private val networkStatus: NetworkStatus,
    private val clock: Clock,
    private val config: LlmGenerationConfig = LlmGenerationConfig.DEFAULT,
) : LlmGenerationQueue {

    override val taskKind: LlmTaskKind = LlmTaskKind.POI_FLAVOR

    override suspend fun drain(): LlmGenerationOutcome {
        if (config.requireUnmeteredNetwork && !networkStatus.isUnmetered()) {
            return skipped(LlmSkipReason.METERED_NETWORK)
        }

        val pois = osmMasterRepository.pois()
        if (pois.isEmpty()) return skipped(LlmSkipReason.NOTHING_TO_GENERATE)

        // 数百件を1件ずつ問い合わせないための一括読み（LlmCacheRepository.entries のKDoc）。
        val cached = cacheRepository.entries(LlmTaskKind.POI_FLAVOR)
        val pending = pois.filterNot { poi -> cached.hasFlavorFor(poi) }
        if (pending.isEmpty()) {
            return LlmGenerationOutcome(taskKind = taskKind, cached = pois.size)
        }

        // 秘密（APIキー）を読むのは、生成する相手が居ると分かってから。
        // 設定が無いなら生成できないが、それは異常ではない（セットアップ前・設定を消した後）。
        val settings = setupRepository.loadLlmConnection()
            ?: return skipped(LlmSkipReason.NOT_CONFIGURED)
        val client = clients.client(settings.format)

        var generated = 0
        var failed = 0
        var remaining = 0

        for (poi in pending) {
            if (generated >= config.maxGenerationsPerRun || failed >= config.maxFailuresPerRun) {
                remaining++
                continue
            }

            val request = PoiFlavorPromptBuilder.request(
                facts = PoiFlavorFacts.of(poi),
                maxTokens = config.poiFlavorMaxTokens,
            )
            val response = try {
                client.generate(request, settings)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: LlmUnavailableException) {
                failed++
                // 設定・通信の問題は次の地点でも同じ結果になる。残りは投げずに見送る
                // （有料APIを無駄に叩かない）。
                if (error.reason.stopsBatch()) {
                    remaining += pending.size - (generated + failed + remaining)
                    break
                }
                continue
            }

            // 形が崩れた応答は保存しない（定型文で凌いで次回リトライ）。
            // 崩れた文章をキャッシュに焼き付けると、二度と直らないまま散歩に出てしまう。
            val flavor = PoiFlavorResponseParser.parse(response.text)
            if (flavor == null) {
                failed++
                continue
            }

            cacheRepository.save(
                LlmCacheEntry(
                    cacheKey = llmCacheKey(LlmTaskKind.POI_FLAVOR, poiFlavorLogicalKey(poi.id)),
                    kind = LlmTaskKind.POI_FLAVOR,
                    promptHash = request.promptHash(),
                    text = flavor,
                    createdAtMs = clock.nowMillis(),
                ),
            )
            generated++
        }

        return LlmGenerationOutcome(
            taskKind = taskKind,
            generated = generated,
            cached = pois.size - pending.size,
            failed = failed,
            remaining = remaining,
        )
    }

    /** 生成済みか（プロンプトが変わっていれば「未生成」として作り直す）。 */
    private fun Map<String, LlmCacheEntry>.hasFlavorFor(poi: Poi): Boolean {
        val entry = get(llmCacheKey(LlmTaskKind.POI_FLAVOR, poiFlavorLogicalKey(poi.id)))
            ?: return false
        val promptHash = PoiFlavorPromptBuilder
            .request(PoiFlavorFacts.of(poi), config.poiFlavorMaxTokens)
            .promptHash()
        return entry.matches(promptHash)
    }

    private fun skipped(reason: LlmSkipReason) =
        LlmGenerationOutcome(taskKind = taskKind, skipReason = reason)
}

/**
 * 依頼の指紋。プロンプト（system / user）だけを見る。
 *
 * `maxTokens` を含めないのは、上限を変えただけで数百件の作り直し＝再課金になるため。
 * 上限は「長く書かせない蓋」であって文章の内容を決める要素ではない。
 */
internal fun LlmRequest.promptHash(): String = PromptHash.of(systemPrompt, userPrompt)

/**
 * この失敗でバッチ全体を打ち切るか
 * （[LlmGenerationConfig.DEFAULT_MAX_FAILURES_PER_RUN] のKDoc「打ち切りの二段構え」）。
 *
 * `when` で全分岐を書くのは、失敗の種類が増えたときに
 * 「打ち切るのか続けるのか」を決め忘れないようにするため。
 *
 * 種別の違う事前バッチ（[PrebatchSpeciesDescriptionUseCase]）でも判断は同じなので共有する。
 * ここが分かれると「地点は諦めたのに図鑑は投げ続ける」という半端な挙動が生まれる。
 */
internal fun LlmFailureKind.stopsBatch(): Boolean = when (this) {
    // 設定・通信の問題。次の地点でも同じ結果になる
    LlmFailureKind.NOT_CONFIGURED,
    LlmFailureKind.UNAUTHORIZED,
    LlmFailureKind.NOT_FOUND,
    LlmFailureKind.RATE_LIMITED,
    LlmFailureKind.SERVER_ERROR,
    LlmFailureKind.TIMEOUT,
    LlmFailureKind.NETWORK,
    -> true

    // 応答の形の問題。単発の事故なら次の地点は通るので、失敗の上限まで続ける
    LlmFailureKind.MALFORMED_RESPONSE,
    LlmFailureKind.EMPTY_RESPONSE,
    LlmFailureKind.UNEXPECTED,
    -> false
}

package com.walkingrpg.shared.domain.llm

import com.walkingrpg.shared.domain.Clock
import com.walkingrpg.shared.domain.codex.Species
import com.walkingrpg.shared.domain.codex.SpeciesCatalog
import com.walkingrpg.shared.domain.setup.SetupRepository
import kotlinx.coroutines.CancellationException

/**
 * 図鑑の記述文を事前に作っておく（design.md §7・architecture.md §5）。
 *
 * ```
 * 種カタログ（手書き・有限）
 *   → キャッシュに無い／プロンプトが変わった種だけを選ぶ
 *   → 上限まで生成して llm_cache に保存
 * ```
 *
 * [PrebatchPoiFlavorUseCase] と同じ流儀で作ってある（従量回線の抑止・件数と失敗の上限・
 * 失敗は保存しない）。違いは材料の出どころだけ：
 *
 * - 地点フレーバーの材料は**POIマスタ**（対象圏を取り直すと入れ替わる、数百件）
 * - 図鑑の記述文の材料は**手書きのカタログ**（[SpeciesCatalog]、十数件で増減しない）
 *
 * ## 発見してから作らない
 * 種を発見した瞬間に生成すると、圏外・レート制限がそのまま
 * 「せっかく10回通って出したのに文章が出ない」になる。**発見は嬉しさの頂点**なので、
 * ここだけは事前生成で必ず埋めておく。カタログは十数件しかないので、
 * 初回のドレイン1回で全部揃う（[LlmGenerationConfig.maxGenerationsPerRun] = 40）。
 *
 * 揃っていなくても定型文（[SpeciesDescriptionFallback]）で成立するので、
 * 生成が間に合わなくても図鑑は開ける（design.md §7）。
 */
class PrebatchSpeciesDescriptionUseCase(
    private val cacheRepository: LlmCacheRepository,
    private val setupRepository: SetupRepository,
    private val clients: LlmClientSelector,
    private val networkStatus: NetworkStatus,
    private val clock: Clock,
    private val config: LlmGenerationConfig = LlmGenerationConfig.DEFAULT,
    /** 対象の種。差し替えられるのはテストのためで、本番は手書きのカタログ全部。 */
    private val species: List<Species> = SpeciesCatalog.ALL,
) : LlmGenerationQueue {

    override val taskKind: LlmTaskKind = LlmTaskKind.SPECIES_DESCRIPTION

    override suspend fun drain(): LlmGenerationOutcome {
        if (config.requireUnmeteredNetwork && !networkStatus.isUnmetered()) {
            return skipped(LlmSkipReason.METERED_NETWORK)
        }

        if (species.isEmpty()) return skipped(LlmSkipReason.NOTHING_TO_GENERATE)

        val cached = cacheRepository.entries(LlmTaskKind.SPECIES_DESCRIPTION)
        val pending = species.filterNot { cached.hasDescriptionFor(it) }
        if (pending.isEmpty()) {
            return LlmGenerationOutcome(taskKind = taskKind, cached = species.size)
        }

        // 秘密（APIキー）を読むのは、生成する相手が居ると分かってから。
        val settings = setupRepository.loadLlmConnection()
            ?: return skipped(LlmSkipReason.NOT_CONFIGURED)
        val client = clients.client(settings.format)

        var generated = 0
        var failed = 0
        var remaining = 0

        for (one in pending) {
            if (generated >= config.maxGenerationsPerRun || failed >= config.maxFailuresPerRun) {
                remaining++
                continue
            }

            val request = SpeciesDescriptionPromptBuilder.request(
                facts = SpeciesDescriptionFacts.of(one),
                maxTokens = config.speciesDescriptionMaxTokens,
            )
            val response = try {
                client.generate(request, settings)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: LlmUnavailableException) {
                failed++
                // 設定・通信の問題は次の種でも同じ結果になる。残りは投げずに見送る
                // （有料APIを無駄に叩かない）。
                if (error.reason.stopsBatch()) {
                    remaining += pending.size - (generated + failed + remaining)
                    break
                }
                continue
            }

            // 形が崩れた応答は保存しない（定型文で凌いで次回リトライ）。
            val description = SpeciesDescriptionResponseParser.parse(response.text)
            if (description == null) {
                failed++
                continue
            }

            cacheRepository.save(
                LlmCacheEntry(
                    cacheKey = descriptionCacheKey(one.id),
                    kind = LlmTaskKind.SPECIES_DESCRIPTION,
                    promptHash = request.promptHash(),
                    text = description,
                    createdAtMs = clock.nowMillis(),
                ),
            )
            generated++
        }

        return LlmGenerationOutcome(
            taskKind = taskKind,
            generated = generated,
            cached = species.size - pending.size,
            failed = failed,
            remaining = remaining,
        )
    }

    /** 生成済みか（プロンプトが変わっていれば「未生成」として作り直す）。 */
    private fun Map<String, LlmCacheEntry>.hasDescriptionFor(species: Species): Boolean {
        val entry = get(descriptionCacheKey(species.id)) ?: return false
        return entry.matches(speciesPromptHash(species, config))
    }

    private fun skipped(reason: LlmSkipReason) =
        LlmGenerationOutcome(taskKind = taskKind, skipReason = reason)
}

/** 図鑑の記述文のキャッシュキー。生成側と読み出し側（[GetSpeciesDescriptionUseCase]）で共有する。 */
internal fun descriptionCacheKey(speciesId: String): String =
    llmCacheKey(LlmTaskKind.SPECIES_DESCRIPTION, speciesDescriptionLogicalKey(speciesId))

/** 種1件のプロンプト指紋。生成側と読み出し側が同じ計算をしないとキャッシュが当たらない。 */
internal fun speciesPromptHash(species: Species, config: LlmGenerationConfig): String =
    SpeciesDescriptionPromptBuilder
        .request(SpeciesDescriptionFacts.of(species), config.speciesDescriptionMaxTokens)
        .promptHash()

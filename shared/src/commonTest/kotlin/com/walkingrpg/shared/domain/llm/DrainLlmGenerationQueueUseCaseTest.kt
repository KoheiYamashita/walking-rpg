package com.walkingrpg.shared.domain.llm

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 生成キューのドレイン（[DrainLlmGenerationQueueUseCase]）。
 *
 * 見たいのは3つ：
 * - **多重実行を防ぐ**（起動時の生成と散歩終了時の生成が重なっても、同じ生成を二度投げない）
 * - キューが投げてしまっても呼び出し側（帰宅後フロー）を巻き添えにしない
 * - **登録した全部のキューが流れる**（引数で受ける形にした理由がこれ。#13 で2本目、
 *   #15 で3本目が増えた）
 */
class DrainLlmGenerationQueueUseCaseTest {

    private fun useCase(
        walkReviewRemarkQueue: LlmGenerationQueue = FakeLlmGenerationQueue(
            taskKind = LlmTaskKind.WALK_REVIEW_REMARK,
            outcome = LlmGenerationOutcome(LlmTaskKind.WALK_REVIEW_REMARK),
        ),
        speciesDescriptionQueue: LlmGenerationQueue = FakeLlmGenerationQueue(
            taskKind = LlmTaskKind.SPECIES_DESCRIPTION,
            outcome = LlmGenerationOutcome(LlmTaskKind.SPECIES_DESCRIPTION),
        ),
        poiFlavorQueue: LlmGenerationQueue = FakeLlmGenerationQueue(),
    ) = DrainLlmGenerationQueueUseCase(
        walkReviewRemarkQueue = walkReviewRemarkQueue,
        speciesDescriptionQueue = speciesDescriptionQueue,
        poiFlavorQueue = poiFlavorQueue,
    )

    @Test
    fun キューの結果をまとめて返す() = runTest {
        val queue = FakeLlmGenerationQueue(
            outcome = LlmGenerationOutcome(LlmTaskKind.POI_FLAVOR, generated = 3, failed = 1),
        )

        val result = useCase(poiFlavorQueue = queue).invoke()

        assertEquals(3, result.outcomes.size)
        assertEquals(3, result.generatedCount)
        assertEquals(1, result.failedCount)
    }

    @Test
    fun 登録した全部のキューが流れる() = runTest {
        // 引数で受けるのは登録漏れをコンパイラとDI検証に見つけさせるため
        // （DrainLlmGenerationQueueUseCase のKDoc「キューの並び」）
        val remark = FakeLlmGenerationQueue(
            taskKind = LlmTaskKind.WALK_REVIEW_REMARK,
            outcome = LlmGenerationOutcome(LlmTaskKind.WALK_REVIEW_REMARK, generated = 1),
        )
        val species = FakeLlmGenerationQueue(
            taskKind = LlmTaskKind.SPECIES_DESCRIPTION,
            outcome = LlmGenerationOutcome(LlmTaskKind.SPECIES_DESCRIPTION, generated = 2),
        )
        val flavor = FakeLlmGenerationQueue(
            outcome = LlmGenerationOutcome(LlmTaskKind.POI_FLAVOR, generated = 5),
        )

        val result = useCase(
            walkReviewRemarkQueue = remark,
            speciesDescriptionQueue = species,
            poiFlavorQueue = flavor,
        ).invoke()

        assertEquals(1, remark.drainCount)
        assertEquals(1, species.drainCount)
        assertEquals(1, flavor.drainCount)
        assertEquals(8, result.generatedCount)
        assertEquals(
            listOf(
                LlmTaskKind.WALK_REVIEW_REMARK,
                LlmTaskKind.SPECIES_DESCRIPTION,
                LlmTaskKind.POI_FLAVOR,
            ),
            result.outcomes.map { it.taskKind },
            "いま効く順（帰宅直後に読む一言が先）",
        )
    }

    @Test
    fun 同時に呼ばれても直列に流す() = runTest {
        // 起動直後に散歩を畳んだ状況。直列化していないと両方が同じ地点を「未生成」と読み、
        // 有料APIを二度叩く
        val queue = FakeLlmGenerationQueue(suspendWhileDraining = true)
        val useCase = useCase(poiFlavorQueue = queue)

        val first = async { useCase() }
        val second = async { useCase() }
        first.await()
        second.await()

        assertEquals(2, queue.drainCount, "呼び出しは両方とも実行される")
        assertEquals(1, queue.maxConcurrency, "ただし同時には走らない")
    }

    @Test
    fun キューが投げても呼び出し側には投げない() = runTest {
        // キューの実装は投げない契約だが、バグで投げたときに帰宅後フローを止めない
        val queue = FakeLlmGenerationQueue(failure = IllegalStateException("バグ"))

        val result = useCase(poiFlavorQueue = queue).invoke()

        assertEquals(1, result.failedCount)
        assertEquals(0, result.generatedCount)
    }
}

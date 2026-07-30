package com.walkingrpg.shared.domain.llm

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 未生成のテキストをまとめて作る入口（architecture.md §5「帰宅後 → LLM生成キューへ投入」）。
 *
 * ```
 * キャッシュに無いもの（＝未生成）を数え直す
 *   → 上限まで生成して llm_cache に保存
 *   → 取れなかったぶんは保存しない＝次回リトライ
 * ```
 *
 * ## 呼び出し口は2つ、実装は1本
 * - **アプリの起動時**：家に居る＝Wi-Fiの見込みが高い。次の散歩までに埋めておく
 * - **散歩の終了時**：天候の後付け取得のあと（数値→通信の順。architecture.md §5
 *   「数値は即時、文章は遅延OK」）
 *
 * どちらも「未生成を全部見る」だけなので引数を取らず1本にしてある。
 * `FetchMissingSessionWeatherUseCase` と同じ設計で、片方の経路にしか無いバグが生まれない。
 *
 * ## 実行は直列（[Mutex]）
 * 上の2つは並行しうる（起動直後に散歩を畳んだ場合）。「未生成を数える→生成する→保存する」は
 * 不可分ではないので、並行に走ると両方が同じ地点を未生成と見なして**同じ生成を二度投げる**。
 * 保存は置き換えなので状態は壊れないが、**有料のAPIを二度叩く**。
 * そのため実行全体を [Mutex] で直列化する。後から来た実行は前の実行が保存し終えた
 * 状態を読むので、二重に投げない。
 *
 * これが効くのは呼び出し側が同じインスタンスを使うときだけなので、
 * DI（`sharedModule`）では `single` で登録してある。
 *
 * ## キューの並び
 * 地点フレーバー（#14）と図鑑の記述文（#13）の2本。#16（振り返り文）が増えるときも
 * **コンストラクタに引数を足す**：`List<LlmGenerationQueue>` で受けると、
 * 登録漏れが実行時（何も生成されない）にしか分からない。引数で受ければ
 * DIの検証（`SharedModuleVerifyTest`）とコンパイラが漏れを見つける。
 *
 * 並びは「件数の多い順」ではなく**外れても困らない順**にしてある：
 * 記述文は種が有限（十数件）で、発見した瞬間に文章が無いのがいちばん痛い（design.md §4.4）ので
 * 先に埋める。地点フレーバーは数百件あるが、未生成の地点は定型文でそのまま成立する。
 */
class DrainLlmGenerationQueueUseCase(
    private val speciesDescriptionQueue: LlmGenerationQueue,
    private val poiFlavorQueue: LlmGenerationQueue,
) {
    /** 実行を直列化する（上記「実行は直列」）。 */
    private val mutex = Mutex()

    suspend operator fun invoke(): Result = mutex.withLock { drainAll() }

    private suspend fun drainAll(): Result {
        val outcomes = mutableListOf<LlmGenerationOutcome>()
        // 1つのキューが投げた例外で残りを止めない（キューの実装は投げない契約だが、
        // バグで投げたときに帰宅後フローを巻き添えにしないための保険）。
        for (queue in queues) {
            outcomes += try {
                queue.drain()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                LlmGenerationOutcome(taskKind = queue.taskKind, failed = 1)
            }
        }
        return Result(outcomes.toList())
    }

    private val queues: List<LlmGenerationQueue>
        get() = listOf(speciesDescriptionQueue, poiFlavorQueue)

    /** ドレイン1回の結果（キューごと）。 */
    data class Result(
        val outcomes: List<LlmGenerationOutcome> = emptyList(),
    ) {
        val generatedCount: Int get() = outcomes.sumOf { it.generated }
        val failedCount: Int get() = outcomes.sumOf { it.failed }
    }
}

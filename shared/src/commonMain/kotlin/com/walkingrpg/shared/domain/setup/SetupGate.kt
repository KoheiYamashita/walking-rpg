package com.walkingrpg.shared.domain.setup

/**
 * ウィザードの進行判定（純関数）。
 *
 * 「どこで止めるか」を1箇所に集めておくと、UIは判定を持たずに
 * ボタンのenabledを決められる（architecture.md §2「Composableは描画とイベント送出のみ」）。
 *
 * 決定事項（design.md §9）：
 * - **LLMの疎通が通るまでプレイを開始できない**。ここが唯一の必須ゲート
 * - 天候は既定がキー不要のOpen-Meteoなので実質スキップ可
 * - 自宅登録は任意（測位できない場所でウィザードが詰まないようにする。#20で後から設定できる）
 * - 対象圏の取り込みは必須。マスタがないとゲームが成立しない
 */
object SetupGate {

    /** [step] から次のステップへ進んでよいか。 */
    fun canAdvance(step: SetupStep, progress: SetupProgress): Boolean = when (step) {
        SetupStep.WELCOME -> true
        SetupStep.LLM -> progress.llmVerified
        SetupStep.WEATHER -> progress.weatherReady
        SetupStep.HOME -> true
        SetupStep.AREA -> progress.areaImported
        SetupStep.DONE -> isComplete(progress)
    }

    /** セットアップ完了とみなせるか（＝ホームに入ってよいか）。 */
    fun isComplete(progress: SetupProgress): Boolean =
        progress.llmVerified && progress.areaImported

    /** 次のステップ。最後なら `null`。 */
    fun next(step: SetupStep): SetupStep? =
        SetupStep.entries.getOrNull(step.ordinal + 1)

    /** 前のステップ。最初なら `null`。 */
    fun previous(step: SetupStep): SetupStep? =
        SetupStep.entries.getOrNull(step.ordinal - 1)
}

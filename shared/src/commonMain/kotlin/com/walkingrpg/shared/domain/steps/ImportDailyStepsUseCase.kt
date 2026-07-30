package com.walkingrpg.shared.domain.steps

/**
 * 歩数計から「昨日・今日」を取り込んで `step_import` に残す（design.md §3「開始を押し忘れた日」）。
 *
 * 位置が無いので街は育たない。ここでやるのは「歩いたという事実を残す」ことだけで、
 * それを翌日のパートナーが言及する（`GetWalkRemarkContextUseCase`。issue #16）ための材料になる。
 *
 * **対象日は昨日と今日の2日だけ**。押し忘れの救済に必要なのは直近であって履歴ではないし、
 * 起動のたびに過去何日ぶんも歩数計に問い合わせると、得るものの無い取得を毎回繰り返すことになる。
 * 昨日を含めるのは、#16 が「昨日の歩数」を見るのに、その日のうちに取り込めていたとは限らないから
 * （日付が変わってから最初の起動で確定させる）。今日を含めるのは、その日のうちに
 * 起動しなくなっても値が残るようにするため。
 *
 * **何度呼んでも同じ状態になる**（[StepImportRepository.upsert] が日付で置き換えるので、
 * 起動のたびに走らせても1日1行に収束する）。取れなかった日は保存しない＝
 * 既にある行を「取れなかった」で上書きして消してしまうことはない。
 *
 * **散歩セッションを記録できている日でも取り込む**。`step_import` は歩数計が観測した事実で、
 * セッションの有無とは別系統（architecture.md §4）。ここでセッションの有無を見て
 * 取り込みを止めると、「取り込んだ後にセッションが記録された日」だけ行が残るという
 * 呼び出し順に依存した状態になり、冪等でなくなる。
 * 「押し忘れの日として言及するか」の判断は、両方を見られる #16 側で行う。
 *
 * 呼び出しタイミングは**アプリ起動時**（`AppViewModel` の起動ブロック。issue #16）。
 * 失敗は握る：歩数が取れなくても散歩の記録には欠けが出ない。
 */
class ImportDailyStepsUseCase(
    private val source: DailyStepsSource,
    private val repository: StepImportRepository,
    private val calendarDays: CalendarDays,
) {
    /**
     * @return 実際に保存できた日ぶん（歩数計から取れなかった日は含まない）。
     */
    suspend operator fun invoke(): List<StepImport> {
        val today = calendarDays.today()
        val days = listOf(calendarDays.previousDay(today), today)

        return days.mapNotNull { day ->
            val daily = source.dailySteps(day) ?: return@mapNotNull null
            val stepImport = StepImport(
                day = day,
                steps = daily.steps,
                distanceEstimateMeters = daily.distanceEstimateMeters,
            )
            repository.upsert(stepImport)
            stepImport
        }
    }
}

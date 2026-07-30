package com.walkingrpg.shared.domain.steps

/**
 * 押し忘れ救済（`step_import`）の永続化境界（architecture.md §2「Repository」）。
 *
 * ドメイン層はこのインターフェースだけを知る。SQLDelightのスキーマ変換は
 * データ層（`StepImportRepositoryImpl`）に閉じる。
 */
interface StepImportRepository {

    /**
     * 1日ぶんを保存する。同じ日の行があれば置き換える（1日1行）。
     *
     * 歩数計の値は後から確定する（同期が遅れて増える）ので、最後に取れた値で上書きする。
     */
    suspend fun upsert(stepImport: StepImport)

    /** 指定日の記録（無ければ `null`）。 */
    suspend fun stepImport(day: CalendarDay): StepImport?

    /**
     * 全ての記録を日付順に返す。
     *
     * パートナーの一言（`GetWalkRemarkContextUseCase`）はこちらを引く：
     * 「その散歩の日より前で、記録が無いのに歩いた日」を探すので、
     * 1日ずつ日付を指定するより全件から絞るほうが素直（1日1行しか無い表）。
     */
    suspend fun stepImports(): List<StepImport>
}

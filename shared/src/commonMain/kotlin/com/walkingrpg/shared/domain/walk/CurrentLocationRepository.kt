package com.walkingrpg.shared.domain.walk

/**
 * 「いまどこにいるか」を1点だけ取る境界（architecture.md §2「Repository」）。
 *
 * 記録用の連続測位（[WalkRecorder]）とは用途が違い、地図を開いたときに
 * カメラを寄せるためだけに使う。取れなければ `null` を返し、例外にしない
 * （現在地は「取れたら使う」情報で、取れなくても地図は出す）。
 */
interface CurrentLocationRepository {

    /** 権限がない・測位できない・時間内に取れない場合は `null`。 */
    suspend fun currentFix(): LocationFix?
}

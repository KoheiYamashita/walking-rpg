package com.walkingrpg.shared.domain.growth

import kotlinx.coroutines.flow.StateFlow

/**
 * 「直近の再計算で段階が上がった道」の置き場（architecture.md §2「Repository」）。
 *
 * ここだけは**永続化しない**。理由は2つ：
 *
 * - 強調（design.md §8「わずかな揺らぎ程度」）は散歩から帰ってきた直後の余韻であって、
 *   記録ではない。真実は `way_growth`（さらに遡れば `passage`）が持っていて、
 *   この集合はいつ空になっても地図の色そのものは変わらない。
 * - 永続化すると「いつ消すか」を別途決めなければならなくなる（翌日？次の散歩？
 *   一度見たら？）。どれを選んでも消し忘れた強調が残る方向に転ぶので、
 *   プロセスが死んだら消える、という一番単純な寿命にした。
 *
 * 更新を [StateFlow] で出すのは、再計算の完了を地図画面が待たずに済ませるため。
 * 「散歩終了 → 再計算 → ここに書く → 地図が読み直す」の順が流れで決まるので、
 * 地図を開いたままでも古い色が残らない。
 */
interface RecentGrowthRepository {

    /**
     * 直近の再計算で段階が上がった道のID。再計算のたびに**置き換わる**
     * （前回の散歩で育った道は、次の散歩の再計算で強調から外れる）。
     */
    val stageRaisedWayIds: StateFlow<Set<Long>>

    /** 再計算1回ぶんの結果を記録する。0件でも呼ぶ（前回の強調を消すため）。 */
    fun record(wayIds: Set<Long>)
}

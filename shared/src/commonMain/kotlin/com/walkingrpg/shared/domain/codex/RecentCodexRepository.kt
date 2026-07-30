package com.walkingrpg.shared.domain.codex

import kotlinx.coroutines.flow.Flow

/**
 * 「直近の再計算で新しく発見された種」の置き場（architecture.md §2「Repository」）。
 *
 * `RecentGrowthRepository`（地図の「今回育った道」）と同じ役目・同じ寿命で、
 * ここだけは**永続化しない**。理由も同じ：
 *
 * - 「今回出た」という強調は帰宅直後の余韻であって記録ではない。記録は
 *   `codex_progress.discovered_at`（さらに遡れば `passage`）が持っていて、
 *   この集合が空になっても図鑑の中身は変わらない
 * - 永続化すると「いつ消すか」を別途決めなければならなくなる。どれを選んでも
 *   消し忘れた強調が残る方向に転ぶので、プロセスが死んだら消える寿命にした
 *
 * 更新を [updates] で出すのは、再計算の完了を図鑑画面が待たずに済ませるため。
 * 「散歩終了 → 再計算 → ここに書く → 図鑑が読み直す」の順が流れで決まる。
 */
interface RecentCodexRepository {

    /**
     * 直近の再計算で新しく発見された種のID。再計算のたびに**置き換わる**
     * （前回の散歩で出た種は、次の散歩の再計算で強調から外れる）。
     */
    val discoveredSpeciesIds: Set<String>

    /**
     * 再計算が走るたびに1回流れる。購読を始めた時点の現在値も1回流れる
     * （画面の初回読み込みがこの購読1本で足りる）。
     *
     * **同じ集合が続いても抑制しない**。ここで流れるのは「値が変わったこと」ではなく
     * 「再計算が終わったこと」で、図鑑は新規発見が0件の散歩でも
     * 予兆の段（[ForeshadowStage]）が進んでいることがある＝読み直す価値がある。
     */
    val updates: Flow<Set<String>>

    /** 再計算1回ぶんの結果を記録する。0件でも呼ぶ（前回の強調を消すため）。 */
    fun record(speciesIds: Set<String>)
}

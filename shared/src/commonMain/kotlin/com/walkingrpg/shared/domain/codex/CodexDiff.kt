package com.walkingrpg.shared.domain.codex

/**
 * 再計算の前後を突き合わせて「新しく発見された種」を求める（純関数）。
 *
 * `codex_progress` は毎回全件を作り直す（[RecomputeCodexProgressUseCase]）ので、
 * 「今回の散歩で何が出たか」はテーブルの中には残らない。作り直しの前後を比べるのが
 * いちばん安いやり方で、これなら差分用の列もテーブルも増えない（`GrowthDiff` と同じ形）。
 *
 * 発見は取り消されない（減衰なし・design.md §4.1）ので、消える向きは考えない。
 * ただし対象圏を狭めて再計算すれば行そのものが消えることはある＝そのときは
 * 「新規発見なし」になるだけで、[newlyDiscoveredSpeciesIds] が負の情報を返すことはない。
 */
object CodexDiff {

    /**
     * [before] では未発見で、[after] で発見済みになった種のID。
     *
     * [before] に行が無かった種（＝今回はじめて訪問した棚の種）も、
     * 到達していれば「新しく発見された」に含める。
     */
    fun newlyDiscoveredSpeciesIds(
        before: List<CodexProgress>,
        after: List<CodexProgress>,
    ): Set<String> {
        val discoveredBefore = before.filter { it.isDiscovered }.mapTo(mutableSetOf()) { it.speciesId }
        return after
            .filter { it.isDiscovered && it.speciesId !in discoveredBefore }
            .mapTo(mutableSetOf()) { it.speciesId }
    }
}

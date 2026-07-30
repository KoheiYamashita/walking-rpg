package com.walkingrpg.shared.data.codex

import com.walkingrpg.shared.domain.codex.RecentCodexRepository
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * [RecentCodexRepository] のプロセス内メモリ実装。
 *
 * DBを使わない理由はインターフェース側のKDocに書いた。DIでは `single` にして、
 * 「再計算する側（散歩終了）」と「読む側（図鑑）」が同じ1個を見るようにする
 * （`InMemoryRecentGrowthRepository` と同じ形）。
 *
 * `replay = 1` の [MutableSharedFlow] を使うのは、購読開始時に現在値が1回ほしい
 * （画面の初回読み込み）一方で、同じ集合が2回続いたときに2回目を落としたくないから
 * （`StateFlow` は後者ができない）。
 */
internal class InMemoryRecentCodexRepository : RecentCodexRepository {

    private val _updates = MutableSharedFlow<Set<String>>(
        replay = 1,
        extraBufferCapacity = UPDATES_BUFFER,
        // 記録する側（再計算）を購読側の都合で待たせない。図鑑の読み直しは
        // 最新の1回さえ届けば結果は同じなので、詰まったら古い方から捨ててよい。
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override var discoveredSpeciesIds: Set<String> = emptySet()
        private set

    override val updates: Flow<Set<String>> = _updates.asSharedFlow()

    init {
        // 一度も散歩していない状態も「現在値」として流す（初回の購読で画面が動き出す）
        _updates.tryEmit(discoveredSpeciesIds)
    }

    override fun record(speciesIds: Set<String>) {
        discoveredSpeciesIds = speciesIds
        _updates.tryEmit(speciesIds)
    }

    private companion object {
        const val UPDATES_BUFFER = 8
    }
}

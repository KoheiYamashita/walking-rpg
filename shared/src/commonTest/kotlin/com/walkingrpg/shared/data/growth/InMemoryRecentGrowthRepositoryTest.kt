package com.walkingrpg.shared.data.growth

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 「今回育った道」の置き場（issue #10）。
 *
 * 見たいのは「再計算が終わったこと」が毎回流れること。値の変化ではないので、
 * 同じ集合が続いても落としてはいけない。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InMemoryRecentGrowthRepositoryTest {

    @Test
    fun 購読を始めた時点の現在値が1回流れる() = runTest {
        val repository = InMemoryRecentGrowthRepository()
        repository.record(setOf(1L))

        val received = mutableListOf<Set<Long>>()
        backgroundScope.launch { repository.updates.collect { received += it } }
        runCurrent()

        // 画面の初回読み込みがこの1回で走る
        assertEquals(listOf(setOf(1L)), received)
    }

    @Test
    fun 同じ集合を続けて記録しても毎回流れる() = runTest {
        // 毎日同じ道を通れば普通に起きる。ここを抑制すると、地図を開いたままの人だけ
        // 2回目の散歩の色が更新されない
        val repository = InMemoryRecentGrowthRepository()
        val received = mutableListOf<Set<Long>>()
        backgroundScope.launch { repository.updates.collect { received += it } }
        runCurrent()

        repository.record(setOf(1L))
        runCurrent()
        repository.record(setOf(1L))
        runCurrent()

        assertEquals(listOf(emptySet(), setOf(1L), setOf(1L)), received)
    }

    @Test
    fun 現在値は最後に記録した集合になる() = runTest {
        val repository = InMemoryRecentGrowthRepository()
        assertEquals(emptySet(), repository.stageRaisedWayIds)

        repository.record(setOf(1L, 2L))
        assertEquals(setOf(1L, 2L), repository.stageRaisedWayIds)

        // 何も育たなかった散歩は前回の強調を消す
        repository.record(emptySet())
        assertEquals(emptySet(), repository.stageRaisedWayIds)
    }
}

package com.walkingrpg.shared.data.growth

import com.walkingrpg.shared.data.db.WalkingRpgDatabase
import com.walkingrpg.shared.domain.growth.GrowthStage
import com.walkingrpg.shared.domain.growth.WayGrowth
import com.walkingrpg.shared.domain.growth.WayGrowthRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [WayGrowthRepository] のSQLDelight実装。
 *
 * 保存は**全削除→挿入を1トランザクション**で行う（WayGrowth.sq）。
 * 途中で失敗しても直前の状態のまま残る＝「半分だけ新しい閾値で計算された成長」は残らない。
 */
internal class WayGrowthRepositoryImpl(
    private val database: WalkingRpgDatabase,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : WayGrowthRepository {

    private val growths get() = database.wayGrowthQueries

    override suspend fun replaceAllGrowths(growths: List<WayGrowth>): Unit = withContext(dispatcher) {
        database.transaction {
            this@WayGrowthRepositoryImpl.growths.deleteAllWayGrowth()
            growths.forEach { growth ->
                this@WayGrowthRepositoryImpl.growths.insertWayGrowth(
                    way_id = growth.wayId,
                    pass_count = growth.passCount.toLong(),
                    stage = growth.stage.name,
                )
            }
        }
    }

    override suspend fun growths(): List<WayGrowth> = withContext(dispatcher) {
        growths.selectAllWayGrowth().executeAsList().mapNotNull { row ->
            toWayGrowth(row.way_id, row.pass_count, row.stage)
        }
    }

    override suspend fun growth(wayId: Long): WayGrowth? = withContext(dispatcher) {
        growths.selectWayGrowth(wayId).executeAsOneOrNull()?.let { row ->
            toWayGrowth(row.way_id, row.pass_count, row.stage)
        }
    }
}

/**
 * 読めない行（段階の名前を変えたあとの古い行・通過0回の行）は**無かったことにする**。
 *
 * `way_growth` はいつ捨ててもよい導出キャッシュ（WayGrowth.sq）なので、
 * 壊れた行に合わせて表示側で例外を捌くより、次の再計算で正しい行に入れ替わるのを待つ方がよい。
 * 真実は `passage` にあるので、これで失われるものは無い。
 */
private fun toWayGrowth(wayId: Long, passCount: Long, stageName: String): WayGrowth? {
    val stage = GrowthStage.entries.firstOrNull { it.name == stageName } ?: return null
    if (passCount < 1L) return null
    return WayGrowth(wayId = wayId, passCount = passCount.toInt(), stage = stage)
}

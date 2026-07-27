package com.walkingrpg.shared.data

import com.walkingrpg.shared.data.db.WalkingRpgDatabase
import com.walkingrpg.shared.platform.DatabaseDriverFactory

/** DBインスタンスの生成。プラットフォーム差はドライバ側に閉じる。 */
internal fun createDatabase(driverFactory: DatabaseDriverFactory): WalkingRpgDatabase =
    WalkingRpgDatabase(driverFactory.create())

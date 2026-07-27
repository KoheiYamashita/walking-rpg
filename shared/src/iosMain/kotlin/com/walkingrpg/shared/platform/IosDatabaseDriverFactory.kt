package com.walkingrpg.shared.platform

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import com.walkingrpg.shared.data.db.WalkingRpgDatabase

internal class IosDatabaseDriverFactory : DatabaseDriverFactory {
    override fun create(): SqlDriver =
        NativeSqliteDriver(WalkingRpgDatabase.Schema, DATABASE_NAME)

    private companion object {
        const val DATABASE_NAME = "walking_rpg.db"
    }
}

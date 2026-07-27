package com.walkingrpg.shared.platform

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.walkingrpg.shared.data.db.WalkingRpgDatabase

internal class AndroidDatabaseDriverFactory(
    private val context: Context,
) : DatabaseDriverFactory {

    override fun create(): SqlDriver = AndroidSqliteDriver(
        schema = WalkingRpgDatabase.Schema,
        context = context,
        name = DATABASE_NAME,
    )

    private companion object {
        /** Android Auto Backup の対象に入る通常のDBパス（architecture.md §6）。 */
        const val DATABASE_NAME = "walking_rpg.db"
    }
}

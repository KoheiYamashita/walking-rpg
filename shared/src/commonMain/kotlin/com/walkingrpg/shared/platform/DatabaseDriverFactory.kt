package com.walkingrpg.shared.platform

import app.cash.sqldelight.db.SqlDriver

/**
 * SQLDelightドライバの生成（Android: AndroidSqliteDriver / iOS: NativeSqliteDriver）。
 *
 * SQLDelightの型を外に出さないよう `internal`。DBはデータ層の内側に閉じる。
 *
 * 実装は必ず `WalkingRpgDatabase.Schema` をドライバに渡すこと。両ドライバとも
 * スキーマのバージョンと保存済みDBの `user_version` を比べて `.sqm` を流すので、
 * これがマイグレーションの唯一の起動点になる（CONTRIBUTING.md「DBマイグレーション」）。
 */
internal interface DatabaseDriverFactory {
    fun create(): SqlDriver
}

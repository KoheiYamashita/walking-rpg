import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.sqldelight)
}

kotlin {
    androidTarget {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            // domain は外部依存なしの純Kotlin（architecture.md §3）。
            // ここに置く依存は data / platform 層でのみ使うこと。
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            // 暦日の計算（SystemCalendarDays）。domain は CalendarDays 越しにしか触らない
            implementation(libs.kotlinx.datetime)

            api(libs.koin.core)

            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)

            implementation(libs.sqldelight.runtime)
            implementation(libs.sqldelight.coroutines.extensions)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
        androidUnitTest.dependencies {
            // .sq のクエリはJVM上のインメモリSQLiteで実際に実行して検証する
            implementation(libs.sqldelight.sqlite.driver)
        }
        androidMain.dependencies {
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.sqldelight.android.driver)

            // platform層（expect/actual）のAndroid実装で使う
            implementation(libs.koin.android)
            implementation(libs.androidx.core.ktx)
            api(libs.androidx.activity)
            implementation(libs.play.services.location)
            implementation(libs.androidx.health.connect)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
            implementation(libs.sqldelight.native.driver)
        }
    }
}

android {
    namespace = "com.walkingrpg.shared"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

sqldelight {
    databases {
        create("WalkingRpgDatabase") {
            packageName.set("com.walkingrpg.shared.data.db")
            // スキーマ定義（.sq）とマイグレーション（.sqm）は
            // src/commonMain/sqldelight/com/walkingrpg/shared/data/db 配下

            // 各バージョンのスキーマを .db として書き出す先（CONTRIBUTING.md「DBマイグレーション」）。
            // sqldelight のソースフォルダ内に置く必要がある：検証タスクは
            // ソースフォルダを走査して .db を集めるので、外に出すと検証対象が0件になる。
            schemaOutputDirectory.set(file("src/commonMain/sqldelight/databases"))

            // .sq（＝新規インストールが作るスキーマ）と
            // .db + .sqm（＝既存DBが辿り着くスキーマ）の食い違いをビルドで落とす。
            // テーブルを足したのに .sqm を書き忘れる事故を、端末で
            // 「no such table」が出るより前に検出するための設定。
            verifyMigrations.set(true)
        }
    }
}

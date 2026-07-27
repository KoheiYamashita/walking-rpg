import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

/**
 * 地図の初期表示位置は「実在の座標をリポジトリに含めない」ため、
 * Git管理外の `local.properties` から読む（未設定なら世界全体を表示する 0,0 / z1）。
 * 設定手順は CONTRIBUTING.md「ビルド」を参照。
 */
val localMapConfig: Properties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun localMapValue(key: String, fallback: String): String =
    (localMapConfig.getProperty(key) ?: fallback).trim().also { it.toDouble() }

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

        buildConfigField("String", "MAP_CENTER_LAT", "\"${localMapValue("map.center.lat", "0.0")}\"")
        buildConfigField("String", "MAP_CENTER_LON", "\"${localMapValue("map.center.lon", "0.0")}\"")
        buildConfigField("String", "MAP_ZOOM", "\"${localMapValue("map.zoom", "1.0")}\"")
    }

    buildFeatures {
        buildConfig = true
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
            // スキーマ定義（.sq）は src/commonMain/sqldelight/com/walkingrpg/shared/data/db 配下
        }
    }
}

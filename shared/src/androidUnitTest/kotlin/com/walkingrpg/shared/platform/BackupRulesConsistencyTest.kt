package com.walkingrpg.shared.platform

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * OS自動バックアップの除外設定（architecture.md §6）が
 * [AndroidSecureStorage.PREFS_NAME] と食い違っていないことを検証する。
 *
 * ## なぜテストで縛るのか
 * `backup_rules.xml` / `data_extraction_rules.xml` の `path` は**ただの文字列**で、
 * `PREFS_NAME` を変えてもビルドは通る。食い違った瞬間に
 * **APIキーの暗号文がクラウドバックアップと端末間移行に乗る**（鍵は Keystore にあるので
 * 復号はできないが、「秘密を端末外に持ち出さない」（design.md §9）は破れる）。
 * しかも気付く機会が実機のバックアップを覗くしかない＝事実上気付けない。
 *
 * 逆向きの事故（除外の行を消してしまった）も同じテストで落ちる。
 *
 * ## 置き場所
 * `PREFS_NAME` は `shared` の `internal` なので、参照できるのはこのモジュールのテストだけ。
 * XMLは `composeApp`（アプリのマニフェストが指す先）にあるので、リポジトリのルートを
 * 探して読む。
 */
class BackupRulesConsistencyTest {

    private val prefsFileName = "${AndroidSecureStorage.PREFS_NAME}.xml"

    @Test
    fun Android11以前のバックアップから秘密のprefsが除外されている() {
        val xml = readRepositoryFile("composeApp/src/androidMain/res/xml/backup_rules.xml")

        assertEquals(
            1,
            excludedSharedPrefPaths(xml).size,
            "sharedpref の除外が1件ではない（増えたなら意図を確かめる）:\n$xml",
        )
        assertTrue(
            prefsFileName in excludedSharedPrefPaths(xml),
            "backup_rules.xml の path が $prefsFileName と食い違っている:\n$xml",
        )
    }

    @Test
    fun Android12以降のクラウドバックアップと端末間移行から除外されている() {
        val xml = readRepositoryFile("composeApp/src/androidMain/res/xml/data_extraction_rules.xml")

        // cloud-backup と device-transfer の**両方**に要る（片方だけだと
        // 端末間移行でキーが運ばれる）
        assertEquals(
            listOf(prefsFileName, prefsFileName),
            excludedSharedPrefPaths(xml),
            "data_extraction_rules.xml の除外が2箇所（cloud-backup / device-transfer）に" +
                "揃っていない:\n$xml",
        )
        assertTrue("<cloud-backup>" in xml, xml)
        assertTrue("<device-transfer>" in xml, xml)
    }

    @Test
    fun 歩行ログのDBとスナップショット画像は除外されていない() {
        // 「絶対に消さない」（design.md §6）の単一障害点対策なので、
        // database / file ドメインの除外を足してはいけない
        listOf(
            "composeApp/src/androidMain/res/xml/backup_rules.xml",
            "composeApp/src/androidMain/res/xml/data_extraction_rules.xml",
        ).forEach { path ->
            val xml = readRepositoryFile(path)

            assertTrue("\"database\"" !in xml, "DBがバックアップから外れている: $path\n$xml")
            assertTrue("\"file\"" !in xml, "画像がバックアップから外れている: $path\n$xml")
        }
    }

    /** `<exclude domain="sharedpref" path="..." />` の path を出てくる順に並べる。 */
    private fun excludedSharedPrefPaths(xml: String): List<String> =
        EXCLUDE_PATTERN.findAll(xml)
            .filter { it.groupValues[1] == "sharedpref" }
            .map { it.groupValues[2] }
            .toList()

    /**
     * リポジトリのルートからの相対パスでファイルを読む。
     *
     * テストの作業ディレクトリはGradleのモジュール（`shared`）だが、そこを固定で
     * `../` するとモジュール構成を変えたときに黙って壊れる。`settings.gradle.kts` を
     * 目印に上へ辿る。
     */
    private fun readRepositoryFile(relativePath: String): String {
        var directory: File? = File(".").absoluteFile
        while (directory != null) {
            if (File(directory, "settings.gradle.kts").isFile) {
                val file = File(directory, relativePath)
                assertTrue(file.isFile, "ファイルが見つからない: ${file.absolutePath}")
                return file.readText()
            }
            directory = directory.parentFile
        }
        error("リポジトリのルート（settings.gradle.kts）が見つからない")
    }

    private companion object {
        val EXCLUDE_PATTERN =
            Regex("""<exclude\s+domain="([^"]+)"\s+path="([^"]+)"\s*/>""", RegexOption.DOT_MATCHES_ALL)
    }
}

package com.walkingrpg.shared.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

/**
 * [AndroidBackupArchive] を実際の `java.util.zip` で検証する。
 *
 * 見たいのは「zipとして本当に往復するか」だけ（中身の意味は
 * `BackupArchiveCodec` の担当）：
 * - バイト列がそのまま戻るか（PNGのような非テキストを壊さないか）
 * - エントリの並びが保たれるか
 * - 空のバックアップが書けて読めるか
 * - **壊れたファイルで例外になるか**（黙って0件を返すと全削除が走る）
 */
class AndroidBackupArchiveTest {

    private val archive = AndroidBackupArchive()

    /** PNGのシグネチャを含む、テキストとして解釈できないバイト列。 */
    private val binaryBytes = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        0x00, 0xFF.toByte(), 0x7F, 0x80.toByte(),
    )

    @Test
    fun 書いたエントリがバイト列ごと戻る() {
        val entries = listOf(
            BackupEntry("manifest.json", """{"schemaVersion":1}""".encodeToByteArray()),
            BackupEntry("snapshots/2026-06.png", binaryBytes),
        )

        val restored = archive.read(archive.write(entries))

        assertEquals(entries, restored, "zipを往復して中身が変わっている")
    }

    @Test
    fun エントリの並びは書いた順のまま戻る() {
        val entries = listOf("c.json", "a.json", "b.json").map {
            BackupEntry(it, it.encodeToByteArray())
        }

        val restored = archive.read(archive.write(entries))

        assertEquals(
            listOf("c.json", "a.json", "b.json"),
            restored.map { it.name },
            "名前順に並べ替わっている（zipは書いた順を保つ）",
        )
    }

    @Test
    fun ディレクトリを含むパスもそのまま扱える() {
        val entries = listOf(BackupEntry("snapshots/2026-06.png", binaryBytes))

        val restored = archive.read(archive.write(entries))

        assertEquals("snapshots/2026-06.png", restored.single().name)
    }

    @Test
    fun 空のバックアップも往復する() {
        // 散歩が1件も無い端末でエクスポートしても落ちない
        val zip = archive.write(emptyList())

        assertTrue(zip.isNotEmpty(), "空でもzipの構造は要る")
        assertTrue(archive.read(zip).isEmpty())
    }

    @Test
    fun 空のエントリも往復する() {
        val entries = listOf(BackupEntry("empty.json", ByteArray(0)))

        assertEquals(entries, archive.read(archive.write(entries)))
    }

    @Test
    fun 同じ入力からは同じバイト列が出る() {
        // エントリの時刻を固定してあるので、書き込み時刻でzipが変わらない
        val entries = listOf(BackupEntry("a.json", "x".encodeToByteArray()))

        assertTrue(archive.write(entries).contentEquals(archive.write(entries)))
    }

    @Test
    fun 壊れたzipは例外になる() {
        // 「読めない」を黙って0件として返すと、インポートが全削除だけを実行してしまう
        val garbage = ByteArray(64) { it.toByte() }

        assertFails { archive.read(garbage) }
    }

    @Test
    fun 途中で切れたzipは例外になる() {
        val zip = archive.write(
            listOf(BackupEntry("a.json", ByteArray(4_096) { 'a'.code.toByte() })),
        )

        assertFails { archive.read(zip.copyOf(zip.size / 2)) }
    }
}

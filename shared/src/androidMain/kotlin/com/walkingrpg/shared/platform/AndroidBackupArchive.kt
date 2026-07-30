package com.walkingrpg.shared.platform

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * [BackupArchive] のAndroid実装（`java.util.zip`）。
 *
 * 依存を足さないのがこの選択の理由：zipはJDKの標準APIにあるので、
 * バックアップのためにライブラリを1本増やす必要が無い。
 *
 * ## 時刻を書かない
 * [ZipEntry.setTime] に固定値を入れて、**同じ入力から必ず同じバイト列が出る**ようにする。
 * 既定では書き込み時のシステム時刻が各エントリに入り、中身が同じzipでも
 * 毎回違うバイト列になる（テストで往復を確かめにくく、差分も読めなくなる）。
 */
internal class AndroidBackupArchive : BackupArchive {

    override fun write(entries: List<BackupEntry>): ByteArray {
        val buffer = ByteArrayOutputStream()
        ZipOutputStream(buffer).use { zip ->
            entries.forEach { entry ->
                zip.putNextEntry(
                    ZipEntry(entry.name).apply { time = FIXED_ENTRY_TIME_MS },
                )
                zip.write(entry.bytes)
                zip.closeEntry()
            }
        }
        return buffer.toByteArray()
    }

    override fun read(zipBytes: ByteArray): List<BackupEntry> {
        requireZipStructure(zipBytes)

        val entries = mutableListOf<BackupEntry>()
        ZipInputStream(zipBytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) {
                    zip.closeEntry()
                    continue
                }
                // ZipEntry.size は圧縮方式によって -1 なので、読み切って測る
                entries += BackupEntry(name = entry.name, bytes = zip.readBytes())
                zip.closeEntry()
            }
        }
        return entries
    }

    /**
     * zipの骨格を先に確かめる。
     *
     * **`ZipInputStream` だけでは壊れたファイルを検出できない**（これが要る理由）：
     * - 中身がzipでないバイト列を渡すと、`nextEntry` はいきなり `null` を返す
     *   ＝「空のバックアップ」と区別が付かない
     * - 末尾が切れたファイル（転送の失敗）でも、`ZipInputStream` は
     *   中央ディレクトリを読まないのでエントリだけ読めて正常終了しうる
     *
     * どちらも「0件のzip」として通ると、インポートが**全削除だけを実行する**。
     * なので、先頭の署名と末尾の End Of Central Directory を自分で確かめる。
     */
    private fun requireZipStructure(zipBytes: ByteArray) {
        val startsWithEntry = zipBytes.hasSignatureAt(0, LOCAL_FILE_HEADER)
        // 空のzipはローカルヘッダを持たず、EOCDだけで始まる
        val startsWithEmptyArchive = zipBytes.hasSignatureAt(0, END_OF_CENTRAL_DIRECTORY)
        if (!startsWithEntry && !startsWithEmptyArchive) {
            throw ZipException("バックアップファイルとして読めません（zipではありません）。")
        }

        // EOCDは末尾にある（コメントが付いていれば最大64KBだけ手前にずれる）
        val searchFrom = (zipBytes.size - END_OF_CENTRAL_DIRECTORY_SEARCH_BYTES).coerceAtLeast(0)
        val hasEnd = (zipBytes.size - END_OF_CENTRAL_DIRECTORY.size downTo searchFrom).any { index ->
            zipBytes.hasSignatureAt(index, END_OF_CENTRAL_DIRECTORY)
        }
        if (!hasEnd) {
            throw ZipException("バックアップファイルが途中で切れています。")
        }
    }

    private fun ByteArray.hasSignatureAt(index: Int, signature: ByteArray): Boolean {
        if (index < 0 || index + signature.size > size) return false
        return signature.indices.all { offset -> this[index + offset] == signature[offset] }
    }

    private companion object {
        /**
         * エントリのタイムスタンプ（2000-01-01 00:00 UTC）。
         *
         * zipのDOS時刻は1980年より前を表せないので、「時刻を持たない」代わりに
         * 表現できる範囲の切りのいい固定値を入れる。**いつ作ったか**は
         * `manifest.json` の `exportedAtMs` が持つ（そちらは中身の一部）。
         */
        const val FIXED_ENTRY_TIME_MS = 946_684_800_000L

        /** `PK\3\4`：エントリ（ローカルファイルヘッダ）の署名。 */
        val LOCAL_FILE_HEADER = byteArrayOf(0x50, 0x4B, 0x03, 0x04)

        /** `PK\5\6`：中央ディレクトリの終端。zipの末尾に必ずある。 */
        val END_OF_CENTRAL_DIRECTORY = byteArrayOf(0x50, 0x4B, 0x05, 0x06)

        /** EOCDを探す範囲（22バイトの固定部＋最大64KBのコメント）。 */
        const val END_OF_CENTRAL_DIRECTORY_SEARCH_BYTES = 22 + 0xFFFF
    }
}

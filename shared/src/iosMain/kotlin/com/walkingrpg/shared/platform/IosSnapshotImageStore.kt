package com.walkingrpg.shared.platform

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSData
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile
import platform.posix.memcpy

/**
 * [SnapshotImageStore] のiOS実装。置き場は `NSDocumentDirectory/snapshots/`。
 *
 * Documents を選ぶ理由（architecture.md §6「OS自動バックアップ」）：
 * - **iCloudバックアップの既定対象**。DBファイル（`NativeSqliteDriver` の既定も Documents）と
 *   一緒に戻ってくる
 * - `NSURLIsExcludedFromBackupKey` は**立てない**。立てるとバックアップから外れて、
 *   design.md §6「スナップショットは絶対に消さない」の単一障害点が端末本体に戻る
 *   （キー・秘密は Keychain 側なので、この領域に秘密は無い）
 * - `NSCachesDirectory` は使わない：OSがいつでも消せる（Androidの `cacheDir` と同じ理由）
 *
 * TODO(#3/#20): iOS実機・シミュレータでの動作確認が未実施
 *  （動作検証はAndroid優先方針。ビルドと配線だけ先に通してある）。
 */
@OptIn(ExperimentalForeignApi::class)
internal class IosSnapshotImageStore : SnapshotImageStore {

    private val fileManager = NSFileManager.defaultManager

    override suspend fun write(relativePath: String, bytes: ByteArray) {
        withContext(Dispatchers.Default) {
            val path = resolve(relativePath)
            path.substringBeforeLast('/', missingDelimiterValue = "")
                .takeIf { it.isNotEmpty() }
                ?.let { directory ->
                    fileManager.createDirectoryAtPath(
                        path = directory,
                        withIntermediateDirectories = true,
                        attributes = null,
                        error = null,
                    )
                }
            // atomically = true は「一時ファイルへ書いてから差し替える」。
            // 途中で落ちたときに壊れた画像が残らない（DBの行より先に画像を書く順序と対になる）。
            bytes.toNSData().writeToFile(path = path, atomically = true)
        }
    }

    override suspend fun read(relativePath: String): ByteArray? = withContext(Dispatchers.Default) {
        NSData.dataWithContentsOfFile(resolve(relativePath))?.toByteArray()
    }

    override suspend fun delete(relativePath: String) {
        withContext(Dispatchers.Default) {
            fileManager.removeItemAtPath(resolve(relativePath), error = null)
        }
    }

    /** 永続領域からの相対パスを絶対パスにする。ルートを知っているのはこのクラスだけ。 */
    private fun resolve(relativePath: String): String {
        val documents = NSSearchPathForDirectoriesInDomains(
            directory = NSDocumentDirectory,
            domainMask = NSUserDomainMask,
            expandTilde = true,
        ).first() as String
        return "$documents/$relativePath"
    }
}

// NSData.create（NSData のイニシャライザ）は BetaInteropApi 扱い。
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun ByteArray.toNSData(): NSData {
    // 空配列の addressOf(0) は例外になるので先に分ける（0バイトのPNGは無いが、防波堤として）
    if (isEmpty()) return NSData()
    return usePinned { pinned ->
        NSData.create(bytes = pinned.addressOf(0), length = size.convert())
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    if (size == 0) return ByteArray(0)
    return ByteArray(size).apply {
        usePinned { pinned -> memcpy(pinned.addressOf(0), bytes, length) }
    }
}

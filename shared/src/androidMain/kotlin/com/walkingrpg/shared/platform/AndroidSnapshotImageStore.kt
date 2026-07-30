package com.walkingrpg.shared.platform

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * [SnapshotImageStore] のAndroid実装。置き場は `filesDir/snapshots/`。
 *
 * `filesDir` を選ぶ理由（architecture.md §6「OS自動バックアップ」）：
 * - **Auto Backup の既定対象**（`android:allowBackup` の既定は true で、
 *   `filesDir` は除外指定をしなければそのまま含まれる）。DBファイルも同じ `data` 配下なので、
 *   「DBとスナップショット画像が一緒に戻ってくる」がOS任せで成立する
 * - `cacheDir` は使わない：OSが空き容量のためにいつでも消せる領域で、
 *   design.md §6「スナップショットは絶対に消さない」と両立しない
 *   （共有用の一時ファイル＝`AndroidFileShare` はあちらでよい）
 * - `getExternalFilesDir` も使わない：端末によっては存在せず（`null`）、
 *   ストレージの権限・スコープの話が絡む
 */
internal class AndroidSnapshotImageStore(
    private val context: Context,
) : SnapshotImageStore {

    override suspend fun write(relativePath: String, bytes: ByteArray) {
        withContext(Dispatchers.IO) {
            val file = resolve(relativePath)
            file.parentFile?.mkdirs()
            file.writeBytes(bytes)
        }
    }

    override suspend fun read(relativePath: String): ByteArray? = withContext(Dispatchers.IO) {
        resolve(relativePath).takeIf { it.isFile }?.readBytes()
    }

    override suspend fun delete(relativePath: String) {
        withContext(Dispatchers.IO) { resolve(relativePath).delete() }
    }

    private fun resolve(relativePath: String): File = File(context.filesDir, relativePath)
}

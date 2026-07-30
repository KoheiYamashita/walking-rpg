package com.walkingrpg.shared.platform

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * FileProvider + ACTION_SEND による共有。
 *
 * ファイルはアプリのキャッシュ配下（`cache/exports`）に書き、
 * 共有先アプリにだけ一時的な読み取り権限を渡す。
 *
 * キャッシュに置くのは意図的：共有先へ渡したあとはOSが消してよい
 * （手元に残すべき原本ではない。残すのはユーザーが選んだ保存先）。
 */
internal class AndroidFileShare(
    private val context: Context,
) : FileShare {

    override suspend fun shareText(fileName: String, mimeType: String, content: String) {
        share(fileName, mimeType) { file -> file.writeText(content) }
    }

    override suspend fun shareBytes(fileName: String, mimeType: String, bytes: ByteArray) {
        share(fileName, mimeType) { file -> file.writeBytes(bytes) }
    }

    /**
     * 一時ファイルへ書いて共有UIを開く。中身の書き方（テキスト/バイト列）だけが
     * 呼び出し側で変わり、置き場と権限の渡し方は共通。
     */
    private suspend fun share(fileName: String, mimeType: String, write: (File) -> Unit) {
        val uri = withContext(Dispatchers.IO) {
            val directory = File(context.cacheDir, EXPORT_DIR).apply { mkdirs() }
            val file = File(directory, fileName)
            write(file)
            FileProvider.getUriForFile(context, "${context.packageName}$AUTHORITY_SUFFIX", file)
        }

        val send = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, fileName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        context.startActivity(
            Intent.createChooser(send, fileName).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }

    private companion object {
        const val EXPORT_DIR = "exports"
        const val AUTHORITY_SUFFIX = ".fileprovider"
    }
}

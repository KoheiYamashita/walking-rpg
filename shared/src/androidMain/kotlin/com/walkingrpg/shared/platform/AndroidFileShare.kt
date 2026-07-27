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
 */
internal class AndroidFileShare(
    private val context: Context,
) : FileShare {

    override suspend fun shareText(fileName: String, mimeType: String, content: String) {
        val uri = withContext(Dispatchers.IO) {
            val directory = File(context.cacheDir, EXPORT_DIR).apply { mkdirs() }
            val file = File(directory, fileName)
            file.writeText(content)
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

package com.walkingrpg.shared.platform

/**
 * テキストファイルをOSの共有UIに渡すプラットフォーム境界
 * （Android: FileProvider + ACTION_SEND / iOS: UIActivityViewController）。
 */
interface FileShare {

    /**
     * [content] を [fileName] という名前の一時ファイルとして書き出し、共有UIを開く。
     *
     * 保存先はアプリのキャッシュ領域。ユーザーが選んだ共有先以外に出ていかない。
     */
    suspend fun shareText(fileName: String, mimeType: String, content: String)
}

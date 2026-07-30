package com.walkingrpg.shared.platform

/**
 * ファイルをOSの共有UIに渡すプラットフォーム境界
 * （Android: FileProvider + ACTION_SEND / iOS: UIActivityViewController）。
 */
interface FileShare {

    /**
     * [content] を [fileName] という名前の一時ファイルとして書き出し、共有UIを開く。
     *
     * 保存先はアプリのキャッシュ領域。ユーザーが選んだ共有先以外に出ていかない。
     */
    suspend fun shareText(fileName: String, mimeType: String, content: String)

    /**
     * [bytes] を [fileName] という名前の一時ファイルとして書き出し、共有UIを開く
     * （手動エクスポートのzip。architecture.md §6）。
     *
     * [shareText] と経路は同じで、違いは中身が文字列かバイト列かだけ。
     * 2本に分けてあるのは、zipを文字列にすると壊れるため
     * （String は不正なバイト列を保てない）。
     */
    suspend fun shareBytes(fileName: String, mimeType: String, bytes: ByteArray)
}

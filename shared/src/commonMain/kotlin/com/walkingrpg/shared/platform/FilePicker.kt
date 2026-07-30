package com.walkingrpg.shared.platform

/**
 * OSのファイル選択UIからバイト列を受け取る境界
 * （Android: `ActivityResultContracts.OpenDocument` / iOS: `UIDocumentPickerViewController`）。
 *
 * [FileShare] の逆向き。書き出しと読み込みで別の境界にしているのは、
 * 読み込みだけがActivity（画面）のライフサイクルに縛られるため
 * （`AndroidFilePicker` のKDoc）。
 */
interface FilePicker {

    /**
     * ファイルを選ばせて中身を返す。ユーザーが選ばずに閉じたら `null`。
     *
     * @param mimeType 選択させたい種別（`application/zip`）。実装は同義の型
     *  （`application/octet-stream` を返すプロバイダ）を足してよい。
     */
    suspend fun pickBytes(mimeType: String): ByteArray?
}

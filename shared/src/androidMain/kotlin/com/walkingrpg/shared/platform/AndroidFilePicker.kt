package com.walkingrpg.shared.platform

import android.content.Context
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [FilePicker] のAndroid実装（`ActivityResultContracts.OpenDocument`）。
 *
 * ファイル選択のUIはActivityがないと出せないので、エントリポイント（`MainActivity`）が
 * [attach] でランチャーを預ける（[AndroidLocationPermissionController] と同じ形。
 * `registerForActivityResult` はSTARTED前に登録が必要なので `onCreate` から呼ぶ）。
 *
 * ## 読み込み専用で、永続的な権限も取らない
 * `OpenDocument` が返すURIの読み取り権限はプロセスが生きている間だけで足りる
 * （中身はその場で全部読んでインポートに渡す。ファイルを持ち続けない）。
 * `takePersistableUriPermission` は呼ばない＝ユーザーのファイルへの参照を残さない。
 */
class AndroidFilePicker(
    private val context: Context,
) : FilePicker {

    private var launcher: ActivityResultLauncher<Array<String>>? = null

    /**
     * 選択の結果を待っているコルーチンへの受け渡し口。
     * 1度に1つしか選ばせない（UI側も実行中はボタンを塞ぐ）。
     */
    private var pending: CompletableDeferred<Uri?>? = null

    /**
     * Activityの `onCreate` から呼ぶ。画面回転などで作り直されたら新しいランチャーで上書きする。
     *
     * 待っている選択は**畳まない**：`ActivityResultRegistry` は選択画面の結果を
     * 作り直し後の新しいコールバックへ届け直し、[pending] はシングルトンのフィールドなので
     * Activityの作り直しをまたいで生きている。ここで `null` で畳むと、選択画面を開いたまま
     * 回転したとき「キャンセル扱い＋選んだファイルは捨てられる」の二重の取りこぼしになる。
     * プロセスごと死んだ場合は [pending] も消えているので、待ちっぱなしにはならない。
     */
    fun attach(activity: ComponentActivity) {
        launcher = activity.registerForActivityResult(
            ActivityResultContracts.OpenDocument(),
        ) { uri ->
            pending?.complete(uri)
            pending = null
        }
    }

    override suspend fun pickBytes(mimeType: String): ByteArray? {
        // Activityが生きていない＝ダイアログを出せない。「選ばれなかった」と同じ扱いにする
        val launcher = this.launcher ?: return null
        val deferred = CompletableDeferred<Uri?>()
        pending?.complete(null)
        pending = deferred

        launcher.launch(arrayOf(mimeType, MIME_TYPE_ANY_BINARY))
        val uri = deferred.await() ?: return null

        return withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }
    }

    private companion object {
        /**
         * zipを `application/octet-stream` として報告するプロバイダ（クラウドストレージ等）が
         * あるので、種別の絞りに足しておく。絞りすぎると、正しいファイルが
         * 選択画面でグレーアウトして「壊れている」ように見える。
         */
        const val MIME_TYPE_ANY_BINARY = "application/octet-stream"
    }
}

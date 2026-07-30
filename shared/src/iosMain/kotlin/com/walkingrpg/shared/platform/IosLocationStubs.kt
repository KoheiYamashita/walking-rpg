package com.walkingrpg.shared.platform

import com.walkingrpg.shared.domain.walk.LocationFix
import com.walkingrpg.shared.domain.walk.LocationPermissionStatus
import com.walkingrpg.shared.domain.walk.LocationUnavailableException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow

/**
 * iOS側の測位・ファイル入出力まわりのスタブ。
 *
 * 動作検証はAndroid優先方針（issue #2 備考）のため、CoreLocation・共有UI・
 * ファイル選択・zipの実装は iOSスパイク（#3）で入れる。ここでは「黙って0件」ではなく
 * 明示的に失敗させ、未実装であることが実行時にわかるようにしておく。
 */

internal class IosLocationProvider : LocationProvider {
    override fun updates(intervalMs: Long): Flow<LocationFix> = flow {
        // TODO(#3): CLLocationManager による測位を実装する
        throw LocationUnavailableException("iOSの測位は未実装です（#3）")
    }
}

internal class IosLocationPermissionController : LocationPermissionController {
    private val _status = MutableStateFlow(LocationPermissionStatus.UNKNOWN)
    override val status: StateFlow<LocationPermissionStatus> = _status.asStateFlow()

    // TODO(#3): CLLocationManager.authorizationStatus を読む
    override fun refresh() = Unit

    // TODO(#3): requestWhenInUseAuthorization を呼ぶ
    override fun request() = Unit
}

internal class IosSessionKeeper : SessionKeeper {
    // TODO(#3): idle timer の無効化・バックグラウンド測位の可否を検証してから実装する
    override fun start() = Unit
    override fun stop() = Unit
}

internal class IosFileShare : FileShare {
    override suspend fun shareText(fileName: String, mimeType: String, content: String) {
        // TODO(#3): UIActivityViewController で共有する
        throw NotImplementedError("iOSの共有は未実装です（#3）")
    }

    override suspend fun shareBytes(fileName: String, mimeType: String, bytes: ByteArray) {
        // TODO(#3): UIActivityViewController で共有する（手動エクスポートのzip。#18）
        throw NotImplementedError("iOSの共有は未実装です（#3）")
    }
}

/**
 * 手動インポートのファイル選択（issue #18）。
 *
 * TODO(#3): UIDocumentPickerViewController で選ばせる。
 */
internal class IosFilePicker : FilePicker {
    override suspend fun pickBytes(mimeType: String): ByteArray? {
        throw NotImplementedError("iOSのファイル選択は未実装です（#3）")
    }
}

/**
 * 手動エクスポート／インポートのzip入出力（issue #18）。
 *
 * TODO(#3): `libcompression` か Foundation の Archive API でzipを組む
 *  （`java.util.zip` に相当するものがKMP共通には無い）。
 */
internal class IosBackupArchive : BackupArchive {
    override fun write(entries: List<BackupEntry>): ByteArray {
        throw NotImplementedError("iOSのzip書き出しは未実装です（#3）")
    }

    override fun read(zipBytes: ByteArray): List<BackupEntry> {
        throw NotImplementedError("iOSのzip読み込みは未実装です（#3）")
    }
}

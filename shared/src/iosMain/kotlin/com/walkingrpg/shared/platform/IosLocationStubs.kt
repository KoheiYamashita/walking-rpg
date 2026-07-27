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
 * iOS側の測位まわりのスタブ。
 *
 * 動作検証はAndroid優先方針（issue #2 備考）のため、CoreLocation の実装は
 * iOSスパイク（#3）で入れる。ここでは「黙って0件」ではなく明示的に失敗させ、
 * 未実装であることが実行時にわかるようにしておく。
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
}

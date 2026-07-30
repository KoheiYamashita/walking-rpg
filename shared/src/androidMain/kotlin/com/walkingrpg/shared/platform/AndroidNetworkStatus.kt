package com.walkingrpg.shared.platform

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.walkingrpg.shared.domain.llm.NetworkStatus

/**
 * Androidの回線種別判定（[NetworkStatus]）。
 *
 * `NET_CAPABILITY_NOT_METERED` を見る。「Wi-Fiか」ではなくOSの申告する従量性を見るのは、
 * テザリング・従量制Wi-Fi が `TRANSPORT_WIFI` でありながら従量として申告されるため
 * （そこでLLMの事前バッチを走らせたら通信量と課金の事故になる）。
 *
 * `ACCESS_NETWORK_STATE`（normal権限）が要る。宣言は
 * `shared/src/androidMain/AndroidManifest.xml`。
 *
 * 取れなければ `false`（[NetworkStatus.isUnmetered] の契約どおり生成しない側に倒す）。
 * 例外を握るのは他のプラットフォーム実装（[AndroidHaptics] など）と同じ方針で、
 * ここで落ちても散歩の記録には関係がない。
 */
internal class AndroidNetworkStatus(
    private val context: Context,
) : NetworkStatus {

    override fun isUnmetered(): Boolean = runCatching {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }.getOrElse { error ->
        Log.w(TAG, "回線種別が取れませんでした（従量として扱います）", error)
        false
    }

    private companion object {
        const val TAG = "AndroidNetworkStatus"
    }
}

package com.walkingrpg.shared.platform

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.walkingrpg.shared.domain.walk.LocationFix
import com.walkingrpg.shared.domain.walk.LocationUnavailableException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * FusedLocationProvider による測位（architecture.md §5「GPS 1〜3秒間隔」）。
 *
 * - 優先度は [Priority.PRIORITY_HIGH_ACCURACY]（歩行の軌跡が要るので妥協しない）
 * - バッチングはしない（`maxUpdateDelayMillis = 0`）。欠落計測のためリアルタイムに受け取る
 * - 精度フィルタはここではかけない。生ログを残し、フィルタは上位で決める
 */
internal class AndroidLocationProvider(
    private val context: Context,
) : LocationProvider {

    private val client by lazy { LocationServices.getFusedLocationProviderClient(context) }

    @SuppressLint("MissingPermission") // 直前に自前でチェックしている
    override fun updates(intervalMs: Long): Flow<LocationFix> = callbackFlow {
        if (!hasLocationPermission()) {
            throw LocationUnavailableException("位置情報の権限がありません")
        }

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateIntervalMillis(intervalMs)
            .setMaxUpdateDelayMillis(0L)
            .setWaitForAccurateLocation(false)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.locations.forEach { location ->
                    trySend(
                        LocationFix(
                            timestampMs = location.time,
                            latitude = location.latitude,
                            longitude = location.longitude,
                            accuracyMeters = location.accuracy.toDouble(),
                        ),
                    )
                }
            }
        }

        client.requestLocationUpdates(request, callback, Looper.getMainLooper())
            .addOnFailureListener { error ->
                close(LocationUnavailableException("測位を開始できませんでした", error))
            }

        awaitClose { client.removeLocationUpdates(callback) }
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
}

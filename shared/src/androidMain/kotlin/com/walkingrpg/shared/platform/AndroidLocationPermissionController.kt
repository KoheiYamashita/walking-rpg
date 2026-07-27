package com.walkingrpg.shared.platform

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.walkingrpg.shared.domain.walk.LocationPermissionStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Androidのランタイムパーミッション（ACCESS_FINE_LOCATION）。
 *
 * 権限ダイアログはActivityがないと出せないので、エントリポイント（MainActivity）が
 * [attach] でランチャーを預ける。OSのAPIに触るのはこのクラスだけで、
 * 上位層は `LocationPermissionRepository` 越しにしか見えない。
 */
class AndroidLocationPermissionController(
    private val context: Context,
) : LocationPermissionController {

    private val _status = MutableStateFlow(readStatus())
    override val status: StateFlow<LocationPermissionStatus> = _status.asStateFlow()

    private var launcher: ActivityResultLauncher<Array<String>>? = null

    /**
     * Activityの `onCreate` から呼ぶ（`registerForActivityResult` はSTARTED前に登録が必要）。
     * 画面回転などで作り直された場合は新しいActivityのランチャーで上書きする。
     */
    fun attach(activity: ComponentActivity) {
        launcher = activity.registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { refresh() }
        refresh()
    }

    override fun refresh() {
        _status.value = readStatus()
    }

    override fun request() {
        val launcher = this.launcher
        if (launcher == null) {
            // Activityが生きていない＝ダイアログを出せない。状態だけ読み直しておく
            refresh()
            return
        }
        launcher.launch(requestedPermissions())
    }

    private fun readStatus(): LocationPermissionStatus =
        if (isGranted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            LocationPermissionStatus.GRANTED
        } else {
            LocationPermissionStatus.DENIED
        }

    private fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /**
     * 位置情報に加えて通知権限も一緒に要求する。
     * Foreground Service（保険）の常駐通知を表示するために必要（API 33+）。
     */
    private fun requestedPermissions(): Array<String> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()
}

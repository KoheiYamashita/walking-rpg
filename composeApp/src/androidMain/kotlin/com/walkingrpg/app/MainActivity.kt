package com.walkingrpg.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.walkingrpg.shared.platform.AndroidLocationPermissionController
import org.koin.android.ext.android.inject

class MainActivity : ComponentActivity() {

    /**
     * 権限ダイアログはActivityがないと出せないので、
     * プラットフォーム層のコントローラにこのActivityのランチャーを預ける。
     * `registerForActivityResult` はSTARTED前に呼ぶ必要があるため `onCreate` で行う。
     */
    private val locationPermissionController: AndroidLocationPermissionController by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        locationPermissionController.attach(this)
        setContent {
            App()
        }
    }

    override fun onResume() {
        super.onResume()
        // 設定画面で権限を変えて戻ってきた場合に追従する
        locationPermissionController.refresh()
    }
}

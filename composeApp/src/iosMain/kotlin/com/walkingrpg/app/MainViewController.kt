package com.walkingrpg.app

import androidx.compose.ui.window.ComposeUIViewController
import com.walkingrpg.app.di.initKoin

/** iOSエントリポイント。iosApp（SwiftUI）から `MainViewControllerKt.MainViewController()` で呼ぶ。 */
fun MainViewController() = ComposeUIViewController(
    configure = { initKoinOnce() },
) {
    App()
}

private var koinStarted = false

private fun initKoinOnce() {
    if (koinStarted) return
    koinStarted = true
    initKoin()
}

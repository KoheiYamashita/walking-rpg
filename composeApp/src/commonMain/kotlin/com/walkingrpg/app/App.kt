package com.walkingrpg.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.walkingrpg.app.ui.home.HomeScreen
import com.walkingrpg.app.ui.map.MapScreen

/** 画面。ナビゲーションライブラリは画面が増えた時点で導入する。 */
private enum class Screen { Home, Map }

/**
 * アプリのルートComposable。
 *
 * 表示中の画面はUI都合の一時状態なので `remember` で持つ（architecture.md §2の
 * 「`remember` はUI都合の一時状態だけ」に該当）。ドメイン状態はここに持たない。
 */
@Composable
fun App() {
    MaterialTheme {
        Surface {
            var screen by remember { mutableStateOf(Screen.Home) }

            when (screen) {
                Screen.Home -> HomeScreen(onOpenMap = { screen = Screen.Map })
                Screen.Map -> MapScreen(onBack = { screen = Screen.Home })
            }
        }
    }
}

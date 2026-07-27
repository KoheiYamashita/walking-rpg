package com.walkingrpg.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import com.walkingrpg.app.ui.home.HomeScreen

/** アプリのルートComposable。ナビゲーションは画面が増えた時点で導入する。 */
@Composable
fun App() {
    MaterialTheme {
        Surface {
            HomeScreen()
        }
    }
}

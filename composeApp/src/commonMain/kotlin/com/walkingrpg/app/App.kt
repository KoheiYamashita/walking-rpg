package com.walkingrpg.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.walkingrpg.app.ui.KeepScreenOn
import com.walkingrpg.app.ui.home.HomeScreen
import com.walkingrpg.app.ui.map.MapScreen
import com.walkingrpg.app.ui.setup.SetupScreen
import org.koin.compose.viewmodel.koinViewModel

/** 画面。ナビゲーションライブラリは画面が増えた時点で導入する。 */
private enum class Screen { Home, Map }

/**
 * アプリのルートComposable。
 *
 * 表示中の画面はUI都合の一時状態なので `remember` で持つ（architecture.md §2の
 * 「`remember` はUI都合の一時状態だけ」に該当）。ドメイン状態はここに持たない。
 *
 * システムバック（Androidの戻る操作・iOSのスワイプバック）は [BackHandler] で受ける。
 * ホーム以外にいるときだけ有効にするので、ホームでのバックは処理せず素通りし、
 * OS標準の挙動（Androidならアプリ終了）になる。
 *
 * [BackHandler] はCMP 1.11で `NavigationEventHandler` への置き換えが予告されている
 * （まだ動作する）。画面が増えてナビゲーションライブラリを入れるとき（issue #20以降）に
 * そちらへ移す。
 *
 * 記録中の画面ON維持（[KeepScreenOn]）はここ1箇所だけで行う。画面ごとに置くと
 * 画面遷移で `onDispose` が走って消灯が復活するうえ、記録状態を二重に持つことになる。
 *
 * 初回セットアップ（issue #6）のゲートもここで持つ。完了フラグが立つまでは
 * ウィザードしか出さないので、ホーム・地図には入れない（design.md §9の決定事項）。
 */
@Suppress("DEPRECATION")
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun App(viewModel: AppViewModel = koinViewModel()) {
    val keepScreenOn by viewModel.keepScreenOn.collectAsStateWithLifecycle()
    val setupGate by viewModel.setupGate.collectAsStateWithLifecycle()

    MaterialTheme {
        Surface {
            KeepScreenOn(keepScreenOn)

            when (setupGate) {
                // 完了フラグの読み出し中。一瞬ホームが見えてしまわないよう何も出さない
                SetupGateState.Loading -> Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                }

                SetupGateState.Required -> SetupScreen(onCompleted = viewModel::onSetupCompleted)

                SetupGateState.Ready -> MainNavigation()
            }
        }
    }
}

/** セットアップ完了後の画面遷移。 */
@Suppress("DEPRECATION")
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun MainNavigation() {
    var screen by remember { mutableStateOf(Screen.Home) }

    BackHandler(enabled = screen != Screen.Home) { screen = Screen.Home }

    when (screen) {
        Screen.Home -> HomeScreen(onOpenMap = { screen = Screen.Map })
        Screen.Map -> MapScreen(onBack = { screen = Screen.Home })
    }
}

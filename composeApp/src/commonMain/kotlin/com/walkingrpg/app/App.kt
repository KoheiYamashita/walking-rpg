package com.walkingrpg.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.walkingrpg.app.ui.album.AlbumScreen
import com.walkingrpg.app.ui.codex.CodexScreen
import com.walkingrpg.app.ui.home.HomeScreen
import com.walkingrpg.app.ui.map.MapScreen
import com.walkingrpg.app.ui.review.ReviewScreen
import com.walkingrpg.app.ui.setup.SetupScreen
import org.koin.compose.viewmodel.koinViewModel

/**
 * 画面。ナビゲーションライブラリは画面が増えた時点で導入する。
 *
 * `enum` から `sealed interface` にしたのは、振り返り（issue #15）が
 * **どのセッションの振り返りか**という引数を持つため。enum＋別の状態で持つと
 * 「画面はReviewだがIDがnull」という組み合わせが表現できてしまう。
 */
private sealed interface Screen {
    data object Home : Screen
    data object Map : Screen
    data object Codex : Screen

    /** 月次スナップショットのアルバム（design.md §4.5・issue #17）。 */
    data object Album : Screen

    /** 振り返り（design.md §3 の 17:08）。 */
    data class Review(val sessionId: Long) : Screen
}

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
    val pendingReviewSessionId by viewModel.pendingReviewSessionId.collectAsStateWithLifecycle()

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

                SetupGateState.Ready -> MainNavigation(
                    pendingReviewSessionId = pendingReviewSessionId,
                    onReviewOpened = viewModel::onReviewOpened,
                )
            }
        }
    }
}

/**
 * セットアップ完了後の画面遷移。
 *
 * @param pendingReviewSessionId 非nullなら振り返りへ自動で移る（散歩の自動終了・
 *  「おかえり」通知のタップ。`AppViewModel.pendingReviewSessionId`）。移った時点で
 *  [onReviewOpened] を呼んで合図を消す＝戻ったあとに開き直されない。
 */
@Suppress("DEPRECATION")
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun MainNavigation(
    pendingReviewSessionId: Long?,
    onReviewOpened: () -> Unit,
) {
    var screen: Screen by remember { mutableStateOf(Screen.Home) }

    LaunchedEffect(pendingReviewSessionId) {
        val sessionId = pendingReviewSessionId ?: return@LaunchedEffect
        screen = Screen.Review(sessionId)
        onReviewOpened()
    }

    BackHandler(enabled = screen != Screen.Home) { screen = Screen.Home }

    when (val current = screen) {
        Screen.Home -> HomeScreen(
            onOpenMap = { screen = Screen.Map },
            onOpenCodex = { screen = Screen.Codex },
            onOpenAlbum = { screen = Screen.Album },
            // 過去の散歩も同じ画面で開く（振り返りは冪等に組み直せる＝GetWalkReviewUseCase）
            onOpenReview = { sessionId -> screen = Screen.Review(sessionId) },
        )

        Screen.Map -> MapScreen(onBack = { screen = Screen.Home })
        // 図鑑の中の一覧⇄詳細は CodexScreen 自身が持つ（BackHandler はここの1階層しか見ない）
        Screen.Codex -> CodexScreen(onBack = { screen = Screen.Home })
        // 一覧⇄拡大表示は AlbumScreen 自身が持つ（図鑑と同じ形）
        Screen.Album -> AlbumScreen(onBack = { screen = Screen.Home })
        is Screen.Review -> ReviewScreen(
            sessionId = current.sessionId,
            onBack = { screen = Screen.Home },
        )
    }
}

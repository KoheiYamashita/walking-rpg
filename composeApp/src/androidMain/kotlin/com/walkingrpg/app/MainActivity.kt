package com.walkingrpg.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.walkingrpg.shared.domain.review.RequestPendingReviewUseCase
import com.walkingrpg.shared.platform.AndroidLocationPermissionController
import com.walkingrpg.shared.platform.WalkNotifierIntents
import org.koin.android.ext.android.inject

class MainActivity : ComponentActivity() {

    /**
     * 権限ダイアログはActivityがないと出せないので、
     * プラットフォーム層のコントローラにこのActivityのランチャーを預ける。
     * `registerForActivityResult` はSTARTED前に呼ぶ必要があるため `onCreate` で行う。
     */
    private val locationPermissionController: AndroidLocationPermissionController by inject()

    /**
     * 「おかえり」通知のタップから振り返りを開く（design.md §3 の 17:08）。
     *
     * ここでUIの状態を直接触らないのは、通知から来た場合とアプリを開いたまま
     * 散歩を畳んだ場合で経路を分けないため（どちらも `PendingReviewRepository` に集まり、
     * `AppViewModel` が1本で購読する）。
     */
    private val requestPendingReview: RequestPendingReviewUseCase by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        locationPermissionController.attach(this)
        // プロセスが死んでいた状態で通知をタップした場合はこちらに入る
        openReviewIfRequested(intent)
        setContent {
            App()
        }
    }

    /**
     * すでに起動しているときに通知をタップした場合。
     *
     * 通知の PendingIntent は `FLAG_ACTIVITY_SINGLE_TOP` 付き（`AndroidWalkNotifier`）なので、
     * Activityは作り直されずここに届く。[setIntent] しておくのは、以降の
     * `getIntent()` が古い extra を返さないようにするため。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openReviewIfRequested(intent)
    }

    override fun onResume() {
        super.onResume()
        // 設定画面で権限を変えて戻ってきた場合に追従する
        locationPermissionController.refresh()
    }

    private fun openReviewIfRequested(intent: Intent?) {
        val sessionId = intent
            ?.takeIf { it.hasExtra(WalkNotifierIntents.EXTRA_REVIEW_SESSION_ID) }
            ?.getLongExtra(WalkNotifierIntents.EXTRA_REVIEW_SESSION_ID, INVALID_SESSION_ID)
            ?.takeIf { it != INVALID_SESSION_ID }
            ?: return
        // 一度開いたら二度開かない（消費は AppViewModel.onReviewOpened）
        intent.removeExtra(WalkNotifierIntents.EXTRA_REVIEW_SESSION_ID)
        requestPendingReview(sessionId)
    }

    private companion object {
        /** `walk_session.id` は AUTOINCREMENT なので1以上。extra が壊れていたときの番人。 */
        const val INVALID_SESSION_ID = -1L
    }
}

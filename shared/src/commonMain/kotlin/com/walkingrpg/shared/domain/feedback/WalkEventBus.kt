package com.walkingrpg.shared.domain.feedback

import kotlinx.coroutines.flow.Flow

/**
 * 歩行中イベントの通り道（architecture.md §5「イベント発生なら振動1回」）。
 *
 * 出す側（M1では [LiveGrowthEstimator] を回している `WalkFeedbackImpl`）と
 * 受ける側（振動・画面の断片・帰宅後の振り返り）を切り離すための1枚。
 * 種別を足す側（#13 の図鑑予兆）は、ここに流すところまでやれば
 * 振動・レート制限・表示はそのまま効く。
 *
 * ## 永続化しない
 *
 * [RecentGrowthRepository][com.walkingrpg.shared.domain.growth.RecentGrowthRepository] と同じく
 * **プロセス内メモリ**に置く。理由は3つ：
 *
 * - イベントは `passage` からいつでも作り直せる導出であって真実の源ではない。
 *   真実（どの道を何回通ったか）は `location_sample` → `passage` の側にある
 *   （architecture.md §4「導出テーブルはすべて passage から再計算できること」）。
 * - 歩行中に出るのは**見込み**（[LiveGrowthEstimator]）で、確定するのは帰宅後の
 *   再計算。見込みをそのまま保存すると、確定値と食い違ったときに
 *   「どちらが本当か」を後から決められないテーブルが残る。
 * - 振り返り（#15）はまだ無い。振り返りが「その散歩で何が起きたか」を出すときに、
 *   確定した `way_growth` の差分から作るのか、ここに溜めたイベントを読むのかは
 *   #15 で決める。先に永続化すると、決める前にスキーマが固定される。
 *
 * したがって [eventsOf] が返すのは**アプリが生きているあいだの**記録で、
 * 散歩中〜帰宅直後の振り返りには足りる（散歩の途中でプロセスが死んだら、
 * そのときは帰宅後の再計算がすべてを引き直す）。
 * TODO(#15 振り返り): 振り返りの入力をここにするなら、そのとき永続化を検討する。
 */
interface WalkEventBus {

    /**
     * 発生したイベントが1件ずつ流れる。**過去のイベントは replay しない**
     * （歩行中の断片は「いま起きた」ことに意味があり、画面を開き直すたびに
     * 昔の断片が出てくると「何かが起きた」の印にならない）。
     * 溜まったぶんを見たい購読者は [eventsOf] を使う。
     */
    val events: Flow<WalkEvent>

    /**
     * イベントを1件流す。**レート制限はここではかけない**：
     * 制限がかかるのは振動だけで、記録は全件残す（[WalkEvent] のKDoc）。
     */
    fun publish(event: WalkEvent)

    /** その散歩で起きたイベントを発生順に返す（振り返り用の読み口）。 */
    fun eventsOf(sessionId: Long): List<WalkEvent>
}

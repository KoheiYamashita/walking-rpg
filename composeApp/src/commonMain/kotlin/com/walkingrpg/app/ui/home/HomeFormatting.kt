package com.walkingrpg.app.ui.home

import com.walkingrpg.shared.domain.feedback.WalkEvent
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** 表示用の整形。UI都合の変換なのでUI層に置く。 */

/**
 * 歩行中に画面へ出す断片（design.md §3「出るのは1〜2文の断片だけ」）。
 *
 * **中身は出さない**：どの道が何段階になったかは帰宅後の振り返りで開く
 * （issue #12 の完了条件「中身は帰宅後まで分からない」）。ここで道の名前や
 * 段階名を出すと、振動の意味が「印」から「通知」に変わってしまう。
 *
 * 断片テキストのLLM生成（#14）はまだここには来ていないので定型文。
 * 種別が増えたらこの `when` に枝が増える＝網羅漏れはコンパイルエラーで分かる。
 *
 * 図鑑の枝（#13）で気を付けるのは**種名も棚も出さないこと**。
 * design.md §4.4 は残り回数を数字で見せない代わりに予兆で匂わせる設計なので、
 * ここで「水辺の何か」まで踏み込むと、歩行中に予兆が予告に変わる。
 * 出現（[WalkEvent.CodexDiscovered]）も同じ扱い：何が出たかは帰宅後に図鑑で開く。
 */
internal fun walkEventFragment(event: WalkEvent): String = when (event) {
    is WalkEvent.GrowthStageUp -> "何かが起きた"
    is WalkEvent.CodexForeshadow -> "何かの気配がした"
    is WalkEvent.CodexDiscovered -> "何かに出会った気がする"
}

/** 経過時間を `mm:ss` / `h:mm:ss` にする。 */
internal fun formatElapsed(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "$hours:${minutes.pad2()}:${seconds.pad2()}"
    } else {
        "${minutes.pad2()}:${seconds.pad2()}"
    }
}

/** epoch millis を端末のタイムゾーンで `MM/dd HH:mm` にする。 */
internal fun formatTimestamp(epochMillis: Long): String {
    val local = Instant.fromEpochMilliseconds(epochMillis)
        .toLocalDateTime(TimeZone.currentSystemDefault())
    return "${local.monthNumber.pad2()}/${local.dayOfMonth.pad2()} " +
        "${local.hour.pad2()}:${local.minute.pad2()}"
}

/** 小数第1位まで（精度・秒数の表示用）。 */
internal fun formatMeters(meters: Double): String {
    val rounded = (meters * 10).toLong()
    return "${rounded / 10}.${rounded % 10}m"
}

private fun Long.pad2(): String = if (this < 10) "0$this" else "$this"

private fun Int.pad2(): String = if (this < 10) "0$this" else "$this"

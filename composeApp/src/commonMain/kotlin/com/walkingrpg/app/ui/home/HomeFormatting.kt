package com.walkingrpg.app.ui.home

import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** 表示用の整形。UI都合の変換なのでUI層に置く。 */

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

package com.walkingrpg.shared.domain.matching

/**
 * map matching のドメインモデル（architecture.md §4）。
 *
 * 純Kotlin。SQLDelightの行型もプラットフォームAPIもここには現れない。
 */

/**
 * 1回の通過（architecture.md §4 `passage(session_id, way_id, ts)`）。
 *
 * 成長の入力は「初回踏破」ではなく**1回の通過ごと**（design.md §4.1）なので、
 * 同じセッションで同じwayを2回通れば [Passage] も2件になる。
 *
 * @param timestampMs その通過の代表時刻＝その通過で最初にスナップした測位の時刻。
 *  「最初」に固定するのは、同じサンプル列から必ず同じ値が出るようにするため
 *  （平均や中央値だと閾値をまたいだサンプルの増減で値が揺れる）。
 */
data class Passage(
    val sessionId: Long,
    val wayId: Long,
    val timestampMs: Long,
)

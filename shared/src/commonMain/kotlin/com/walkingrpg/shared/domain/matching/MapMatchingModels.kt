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

/**
 * 「ある道を、ある散歩で通った」1件（Passage.sq `selectSessionVisitsByWay` の1行）。
 *
 * [Passage] との違いは**セッション単位で畳んである**こと。同じ散歩で同じ道を3回通っても
 * [Passage] は3件だが、こちらは1件になる。図鑑の訪問回数は「何回通ったか」ではなく
 * 「何回の散歩でそこへ行ったか」なので、この形で数える
 * （[com.walkingrpg.shared.domain.codex.CodexProgressCalculator] のKDoc「訪問回数の定義」）。
 *
 * @param firstTimestampMs その散歩でその道を最初に通った時刻。発見時刻（`discovered_at`）を
 *  端末時計ではなく歩行ログから引くための材料（同上のKDoc「発見時刻」）。
 */
data class SessionVisit(
    val sessionId: Long,
    val firstTimestampMs: Long,
)

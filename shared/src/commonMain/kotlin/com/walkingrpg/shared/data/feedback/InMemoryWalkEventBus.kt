package com.walkingrpg.shared.data.feedback

import com.walkingrpg.shared.domain.feedback.WalkEvent
import com.walkingrpg.shared.domain.feedback.WalkEventBus
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * [WalkEventBus] のプロセス内メモリ実装。
 *
 * DBを使わない理由はインターフェース側のKDocに書いた。DIでは `single` にして、
 * 「出す側（記録の収集コルーチン）」と「読む側（画面）」が同じ1個を見るようにする
 * （`InMemoryRecentGrowthRepository` と同じ流儀）。
 *
 * `replay` を置かないのは、歩行中の断片が「いま起きた」ことの印だから
 * （[WalkEventBus.events] のKDoc）。溜まったぶんは [eventsOf] で読む。
 *
 * 書き込みは記録の収集コルーチン1本からで、並行して呼ばれることはない
 * （[com.walkingrpg.shared.domain.feedback.WalkFeedback] の契約）。
 * [eventsOf] は読み出し時にコピーを返すので、読んでいる最中に追記されても壊れない。
 */
internal class InMemoryWalkEventBus : WalkEventBus {

    private val _events = MutableSharedFlow<WalkEvent>(
        extraBufferCapacity = EVENTS_BUFFER,
        // 出す側（記録）を購読側の都合で待たせない。画面が見られていなければ
        // 断片は誰にも読まれないまま流れるだけで、記録は [eventsOf] に残っている。
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private val recorded = mutableMapOf<Long, MutableList<WalkEvent>>()

    override val events: Flow<WalkEvent> = _events.asSharedFlow()

    override fun publish(event: WalkEvent) {
        // 記録が先。振動・表示が間に合わなくても「振り返りには全部出る」を守る。
        recorded.getOrPut(event.sessionId) { mutableListOf() } += event
        _events.tryEmit(event)
    }

    override fun eventsOf(sessionId: Long): List<WalkEvent> =
        recorded[sessionId].orEmpty().toList()

    private companion object {
        /** 1回の散歩で出るイベントは多くて十数件（レート制限がかかるのは振動だけ）。 */
        const val EVENTS_BUFFER = 16
    }
}

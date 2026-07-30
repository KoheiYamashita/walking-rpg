package com.walkingrpg.shared.domain.snapshot

import com.walkingrpg.shared.domain.Clock
import com.walkingrpg.shared.domain.steps.CalendarDays
import com.walkingrpg.shared.domain.walk.WalkSessionRepository
import com.walkingrpg.shared.platform.SnapshotImageStore

/**
 * まだ作っていない月のスナップショットをまとめて作る（issue #17・design.md §4.5）。
 *
 * 呼び出し口はアプリの起動時（`AppViewModel`）1つだけ。「月末に走らせる」形にしないのは、
 * 月末の23:59に必ずアプリが起きている保証が無いから＝**次に起動したときに気付いて作る**。
 *
 * ## 何月を作るか
 * 「いちばん古い散歩の月」から「先月」までのうち、`snapshot` に行が無い月を全部。
 *
 * - **当月は作らない**：まだ終わっていない月の1枚を焼くと、月の途中の姿が
 *   その月の記憶として固定される（一度作った月は書き換えないので、あとから直せない）
 * - **1ヶ月以上開いても穴が空かない**：3ヶ月アプリを開かなかったら3枚まとめて作る。
 *   「先月ぶんだけ作る」にすると、開かなかった月がアルバムから永久に欠ける
 * - **散歩が1件も無い月は飛ばす**（行を作らない）。歩かなかった月に空白の1枚を挟むのは
 *   design.md §2「時間経過で悪化させない」に反する（開くたびに空白が目に入る）
 *
 * 同じ月を二度作らないので**冪等**：起動のたびに走らせても、枚数もファイルも増えない。
 *
 * ## 書き込みの順序
 * 必ず「画像ファイル → DBの行」。逆にすると、途中で落ちたときに
 * 行だけ残って画像の無い月がアルバムに並ぶ（`Snapshot.sq` のコメント「アルバム割れ」）。
 * 画像だけ残って行が無いのは無害で、次の起動が同じパスに書き直して行を入れる。
 */
class GenerateMonthlySnapshotsUseCase(
    private val walkSessionRepository: WalkSessionRepository,
    private val snapshotRepository: SnapshotRepository,
    private val getMonthlySnapshotScene: GetMonthlySnapshotSceneUseCase,
    private val getMonthlySnapshotStats: GetMonthlySnapshotStatsUseCase,
    private val renderer: MonthlySnapshotRenderer,
    private val imageStore: SnapshotImageStore,
    private val calendarDays: CalendarDays,
    private val clock: Clock,
) {
    /** @return この呼び出しで新しく作った月（古い月から順）。何も作らなければ空。 */
    suspend operator fun invoke(): List<CalendarMonth> {
        val oldestSessionMs = walkSessionRepository.oldestSessionStartedAtMs() ?: return emptyList()
        val oldestMonth = calendarDays.monthOf(calendarDays.day(oldestSessionMs))
        val currentMonth = calendarDays.monthOf(calendarDays.today())

        val created = mutableListOf<CalendarMonth>()
        for (month in monthsToConsider(oldestMonth = oldestMonth, currentMonth = currentMonth)) {
            if (generate(month)) created += month
        }
        return created
    }

    /**
     * 生成対象になりうる月（古い月から先月まで、昇順）。
     *
     * 先月から [CalendarDays.previousMonth] で遡って作る：月の日数もうるう年も暦の知識なので、
     * 「+1ヶ月」をドメインで組み立てない。`previousMonth` は必ず厳密に1つ前へ進むので、
     * [oldestMonth] を下回った時点で必ず止まる。
     *
     * [oldestMonth] が当月なら（記録を始めたのが今月）空＝当月は作らない。
     */
    private fun monthsToConsider(
        oldestMonth: CalendarMonth,
        currentMonth: CalendarMonth,
    ): List<CalendarMonth> {
        val months = mutableListOf<CalendarMonth>()
        var month = calendarDays.previousMonth(currentMonth)
        while (month >= oldestMonth) {
            months += month
            month = calendarDays.previousMonth(month)
        }
        return months.asReversed()
    }

    /**
     * 1ヶ月ぶん作る。既にある月・散歩が1件も無い月は何もしない。
     *
     * @return 新しく行を入れたら `true`。
     */
    private suspend fun generate(month: CalendarMonth): Boolean {
        if (snapshotRepository.snapshot(month) != null) return false

        // 数値を先に出して「歩いた月か」を判定する。散歩が0件の月は絵も描かない
        // （描いても全部の月で同じ絵になる＝生成時点の街の姿しか手元に無い）。
        val stats = getMonthlySnapshotStats(month)
        if (stats.sessionCount == 0) return false

        val scene = getMonthlySnapshotScene(month)
        val bytes = renderer.render(scene = scene, stats = stats)
        val imagePath = month.snapshotImagePath()

        // 画像 → 行の順（KDoc「書き込みの順序」）。
        imageStore.write(relativePath = imagePath, bytes = bytes)
        return snapshotRepository.insertIfAbsent(
            Snapshot(
                month = month,
                imagePath = imagePath,
                stats = stats,
                createdAtMs = clock.nowMillis(),
            ),
        )
    }
}

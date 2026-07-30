package com.walkingrpg.shared.domain.snapshot

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 月次スナップショットの値の型が守る不変条件。 */
class SnapshotModelsTest {

    @Test
    fun 月はYYYY_MM形式でしか作れない() {
        assertEquals("2026-06", CalendarMonth("2026-06").iso)

        // 日が付いている・桁が足りない・月が範囲外は、どれも後段で黙って壊れる形
        listOf("2026-6", "2026-06-01", "202606", "2026-13", "2026-00", "").forEach { invalid ->
            assertFailsWith<IllegalArgumentException>("'$invalid' が通ってしまう") {
                CalendarMonth(invalid)
            }
        }
    }

    @Test
    fun 月は辞書順が時系列順になる() {
        // 生成対象の月を「いちばん古い月まで」で挟むのに使う性質（GenerateMonthlySnapshotsUseCase）
        assertTrue(CalendarMonth("2025-12") < CalendarMonth("2026-01"))
        assertTrue(CalendarMonth("2026-09") < CalendarMonth("2026-10"))
        assertEquals(
            listOf("2025-11", "2025-12", "2026-01"),
            listOf(CalendarMonth("2026-01"), CalendarMonth("2025-11"), CalendarMonth("2025-12"))
                .sorted()
                .map { it.iso },
        )
    }

    @Test
    fun 月の範囲は上端を含まない() {
        val range = MonthRange(fromMs = 100L, untilMs = 200L)

        assertTrue(100L in range, "月初は含む")
        assertTrue(199L in range)
        assertFalse(200L in range, "翌月初は含まない（月末と翌月1日が両方入る隙を作らない）")
        assertFalse(99L in range)
    }

    @Test
    fun 逆さの範囲は作れない() {
        assertFailsWith<IllegalArgumentException> { MonthRange(fromMs = 200L, untilMs = 100L) }
        assertFailsWith<IllegalArgumentException> { MonthRange(fromMs = 100L, untilMs = 100L) }
    }

    @Test
    fun 画像のパスは月から機械的に決まる() {
        // DBの行が無くてもファイルの在り処が分かる（書き込み順が「画像→行」である前提）
        assertEquals("snapshots/2026-06.png", CalendarMonth("2026-06").snapshotImagePath())
        assertTrue(CalendarMonth("2026-06").snapshotImagePath().startsWith(SNAPSHOT_IMAGE_DIRECTORY))
    }

    @Test
    fun 統計は負の値を持てない() {
        assertFailsWith<IllegalArgumentException> {
            MonthlySnapshotStats(
                distanceMeters = -1.0,
                newWayCount = 0,
                discoveredSpeciesNames = emptyList(),
                sessionCount = 0,
            )
        }
    }

    @Test
    fun 保存済みの1枚は空のパスを持てない() {
        assertFailsWith<IllegalArgumentException> {
            Snapshot(
                month = CalendarMonth("2026-06"),
                imagePath = "",
                stats = MonthlySnapshotStats(0.0, 0, emptyList(), 0),
                createdAtMs = 0L,
            )
        }
    }
}

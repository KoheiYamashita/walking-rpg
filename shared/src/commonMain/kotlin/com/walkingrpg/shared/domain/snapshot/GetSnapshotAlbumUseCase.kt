package com.walkingrpg.shared.domain.snapshot

/**
 * アルバム（保存済みの月の一覧）の読み口。**通信しない・作らない。**
 *
 * 生成の入口（[GenerateMonthlySnapshotsUseCase]）と分けてあるのは、
 * 画面を開いたことが生成の合図になってはいけないから：生成には描画（レンダラ）が絡むので、
 * アルバムを開くたびに走らせると、開くのが遅くなるうえ「開かないと増えない」ことになる。
 * 生成は起動時に1回（`AppViewModel`）。
 *
 * 並びは新しい月から（`SnapshotRepository.snapshots`）。
 */
class GetSnapshotAlbumUseCase(
    private val snapshotRepository: SnapshotRepository,
) {
    suspend operator fun invoke(): List<Snapshot> = snapshotRepository.snapshots()
}

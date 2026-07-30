package com.walkingrpg.shared.domain.snapshot

import com.walkingrpg.shared.platform.SnapshotImageStore

/**
 * 保存済みの1枚の中身（PNGのバイト列）を読む。無ければ `null`。
 *
 * ViewModelから [SnapshotImageStore] を直に触らせないために挟んである
 * （architecture.md §2「ViewModelはプラットフォームAPI・DB・HTTPに触らない」）。
 * バイト列を画像（`ImageBitmap`）に開くのはUI層の仕事で、そちらは
 * 描画APIの型なのでドメインにも `UiState` にも載せない。
 *
 * `null` は縮退の合図で、エラーではない：手動インポート（issue #18）で
 * DBだけ入れて画像を入れ忘れた・OSのバックアップ復元が画像に追いついていない、
 * といった状況で起きる。行があるかぎり数値（`stats_json`）は出せるので、
 * アルバムは絵の無いカードとして成立する。
 */
class ReadSnapshotImageUseCase(
    private val imageStore: SnapshotImageStore,
) {
    suspend operator fun invoke(snapshot: Snapshot): ByteArray? =
        imageStore.read(snapshot.imagePath)
}

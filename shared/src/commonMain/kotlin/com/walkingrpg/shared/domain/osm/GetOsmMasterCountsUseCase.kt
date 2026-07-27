package com.walkingrpg.shared.domain.osm

/**
 * 端末DBに入っているマスタの件数を返すUseCase。
 *
 * デバッグUIが「今どれだけ取り込めているか」を出すために使う。
 * 取り込みが冪等であること（再実行しても件数が増えない）の目視確認にもなる。
 */
class GetOsmMasterCountsUseCase(
    private val masterRepository: OsmMasterRepository,
) {
    suspend operator fun invoke(): OsmMasterCounts = masterRepository.counts()
}

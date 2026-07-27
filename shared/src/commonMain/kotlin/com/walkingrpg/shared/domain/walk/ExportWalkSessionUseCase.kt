package com.walkingrpg.shared.domain.walk

/**
 * セッションのサンプルをJSONで書き出して共有する。
 *
 * 書き出し先はユーザーが選ぶ共有先のみ。リポジトリへ自動で持ち込む経路は作らない
 * （実歩行データには自宅位置が含まれるため。フィクスチャ化は #8 の匿名化方針の後）。
 */
class ExportWalkSessionUseCase(
    private val exporter: WalkSessionExporter,
) {
    /** 書き出したファイル名を返す。 */
    suspend operator fun invoke(sessionId: Long): String = exporter.exportSession(sessionId)
}

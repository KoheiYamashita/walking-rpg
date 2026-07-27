package com.walkingrpg.shared.domain.walk

/**
 * セッションのサンプルを外部に書き出す境界。
 *
 * **注意（issue #2）**：実歩行のサンプルには自宅位置が含まれる。
 * 書き出し先はユーザーが選ぶ共有先だけで、リポジトリへ自動コミットする経路は作らない。
 * フィクスチャ化は匿名化方針を決めてから（#8）。
 */
interface WalkSessionExporter {

    /**
     * 指定セッションをJSONとして書き出し、OSの共有UIに渡す。
     * 書き出したファイル名を返す。
     */
    suspend fun exportSession(sessionId: Long): String
}

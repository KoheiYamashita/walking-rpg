package com.walkingrpg.shared.domain

/**
 * 実行環境の情報を取得する境界（architecture.md §2「Repository」）。
 *
 * ドメイン層はインターフェースだけを持ち、実装は data 層に置く。
 * プラットフォーム固有のAPIはこの境界の向こう側に閉じ込める。
 */
interface SystemInfoRepository {
    /** 実行中のプラットフォーム名（例: `Android 36` / `iOS 18.0`）。 */
    val platformName: String
}

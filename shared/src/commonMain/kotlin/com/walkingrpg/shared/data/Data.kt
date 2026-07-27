package com.walkingrpg.shared.data

/**
 * データ層（architecture.md §2 / §3）。
 *
 * - Repository実装・SQLDelight・Ktor（LLM / 天候API）の置き場
 * - ドメインモデル⇄DBスキーマの変換はこの層に閉じる
 *
 * SQLDelightのスキーマ（.sq）とRepository実装は後続issueで追加する。
 */
internal object DataLayer

package com.walkingrpg.shared.domain

/**
 * ドメイン層（architecture.md §2 / §3）。
 *
 * - 純Kotlinのみ。Android / iOS / DB / HTTP のAPIをここから参照しない
 * - UseCase は1操作1クラス。時刻・乱数は使わず `Clock` 等を注入する
 * - Repository は「インターフェースだけ」ここに置き、実装は data 層
 *
 * ドメインモデル・UseCase・Repositoryインターフェースは後続issueで追加する。
 */
internal object DomainLayer

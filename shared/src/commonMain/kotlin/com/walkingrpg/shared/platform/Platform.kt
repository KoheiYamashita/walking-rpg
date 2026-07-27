package com.walkingrpg.shared.platform

/**
 * プラットフォーム層（architecture.md §2 / §3）。
 *
 * Location / Pedometer / Haptics / SessionKeeper / セキュアストレージを
 * expect/actual で実装し、インターフェース越しにDIで注入する。
 * ドメイン層からこの層は見えない。
 *
 * 雛形では動作確認用の [Platform] だけを置く。
 */
interface Platform {
    val name: String
}

expect fun currentPlatform(): Platform

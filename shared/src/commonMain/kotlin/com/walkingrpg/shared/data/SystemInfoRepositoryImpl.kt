package com.walkingrpg.shared.data

import com.walkingrpg.shared.domain.SystemInfoRepository
import com.walkingrpg.shared.platform.Platform

/**
 * [SystemInfoRepository] の実装。expect/actual の [Platform] をドメイン層向けに包む。
 *
 * プラットフォーム層への依存をこのクラスに閉じることで、
 * ドメイン層・UI層からは expect/actual が見えなくなる（architecture.md §2）。
 */
internal class SystemInfoRepositoryImpl(
    private val platform: Platform,
) : SystemInfoRepository {
    override val platformName: String get() = platform.name
}

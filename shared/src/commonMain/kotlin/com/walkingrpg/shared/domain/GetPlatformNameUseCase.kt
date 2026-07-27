package com.walkingrpg.shared.domain

/**
 * 実行中のプラットフォーム名を取得するUseCase。
 *
 * 役割規約（architecture.md §2）：UseCaseは1操作1クラス・純Kotlin。
 * 依存は [SystemInfoRepository] のようなドメイン層のインターフェースのみに限る。
 */
class GetPlatformNameUseCase(
    private val systemInfoRepository: SystemInfoRepository,
) {
    operator fun invoke(): String = systemInfoRepository.platformName
}

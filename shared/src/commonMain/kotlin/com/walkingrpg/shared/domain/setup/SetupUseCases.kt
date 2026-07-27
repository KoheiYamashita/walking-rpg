package com.walkingrpg.shared.domain.setup

import com.walkingrpg.shared.domain.walk.CurrentLocationRepository

/**
 * セットアップのUseCase群（1操作1クラス、純Kotlin。architecture.md §2）。
 *
 * 1ファイルにまとめてあるのは、どれも数行で、同じ1本の導線にしか使わないため。
 * 育ってきたら分割する。
 */

/** 初回起動判定。ホームに入れてよいか。 */
class IsSetupCompletedUseCase(
    private val repository: SetupRepository,
) {
    suspend operator fun invoke(): Boolean = repository.isSetupCompleted()
}

/** 保存済みの設定をまとめて読み出す（ウィザード再開時に入力欄を埋めるため）。 */
class LoadSetupSettingsUseCase(
    private val repository: SetupRepository,
) {
    suspend operator fun invoke(): SavedSetupSettings = SavedSetupSettings(
        llm = repository.loadLlmConnection(),
        weather = repository.loadWeatherSettings(),
        home = repository.loadHomeAnchor(),
    )
}

/**
 * 保存済み設定のスナップショット。
 *
 * `llm.apiKey` と `home` は秘密なので、**UIに出す以外の用途に流さない**
 * （ログ・エクスポートに載せない）。
 */
data class SavedSetupSettings(
    val llm: LlmConnectionSettings?,
    val weather: WeatherSettings,
    val home: HomeAnchor?,
)

/**
 * 疎通テスト。検証 → 1回リクエスト、の1操作。
 *
 * 成功したときだけ設定を保存する。通らない設定を残しても後で困るだけなので、
 * 「疎通が通った設定＝保存されている設定」を保つ。
 */
class TestLlmConnectionUseCase(
    private val tester: LlmConnectionTester,
    private val repository: SetupRepository,
) {
    suspend operator fun invoke(settings: LlmConnectionSettings): LlmConnectionTestResult {
        val normalized = LlmConnectionValidator.normalize(settings)
        val errors = LlmConnectionValidator.validate(normalized)
        if (errors.isNotEmpty()) {
            return LlmConnectionTestResult.Failure(
                reason = LlmConnectionFailure.INVALID_INPUT,
                detail = errors.joinToString("\n") { it.message },
            )
        }

        val result = tester.test(normalized)
        if (result is LlmConnectionTestResult.Success) {
            repository.saveLlmConnection(normalized)
        }
        return result
    }
}

/** 天候プロバイダの選択を保存する。Provider実装は #11 の領分で、ここは保存のみ。 */
class SaveWeatherSettingsUseCase(
    private val repository: SetupRepository,
) {
    suspend operator fun invoke(settings: WeatherSettings): List<WeatherSettingsError> {
        val normalized = WeatherSettingsValidator.normalize(settings)
        val errors = WeatherSettingsValidator.validate(normalized)
        if (errors.isEmpty()) repository.saveWeatherSettings(normalized)
        return errors
    }
}

/**
 * 現在地を自宅として登録する。
 *
 * 座標の入力欄は作らない（CONTRIBUTING.md：ユーザー固有の座標をリポジトリに
 * 持ち込む経路を作らない）。取得元は既存の [CurrentLocationRepository] だけ。
 * 保存先は端末内のセキュアストレージで、ログにも出さない。
 */
class RegisterHomeAnchorUseCase(
    private val currentLocationRepository: CurrentLocationRepository,
    private val repository: SetupRepository,
) {
    /** 現在地が取れなければ `null`（権限がない・測位できない）。 */
    suspend operator fun invoke(blurRadiusMeters: Int): HomeAnchor? {
        val fix = currentLocationRepository.currentFix() ?: return null
        val anchor = HomeAnchor(
            latitude = fix.latitude,
            longitude = fix.longitude,
            blurRadiusMeters = blurRadiusMeters,
        )
        repository.saveHomeAnchor(anchor)
        return anchor
    }
}

/** ぼかし半径だけを変える（自宅の再取得はしない）。 */
class UpdateHomeBlurRadiusUseCase(
    private val repository: SetupRepository,
) {
    suspend operator fun invoke(blurRadiusMeters: Int): HomeAnchor? {
        val current = repository.loadHomeAnchor() ?: return null
        val updated = current.copy(blurRadiusMeters = blurRadiusMeters)
        repository.saveHomeAnchor(updated)
        return updated
    }
}

/**
 * セットアップを完了させる。
 *
 * 完了条件（[SetupGate.isComplete]）を満たしていなければ何もせず `false`。
 * UI側のボタン制御を信用せず、ここでも同じ判定を通す。
 */
class CompleteSetupUseCase(
    private val repository: SetupRepository,
) {
    suspend operator fun invoke(progress: SetupProgress): Boolean {
        if (!SetupGate.isComplete(progress)) return false
        repository.markSetupCompleted()
        return true
    }
}

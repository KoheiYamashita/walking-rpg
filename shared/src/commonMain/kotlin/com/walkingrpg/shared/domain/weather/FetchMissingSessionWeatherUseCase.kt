package com.walkingrpg.shared.domain.weather

import com.walkingrpg.shared.domain.Clock
import com.walkingrpg.shared.domain.setup.SetupRepository
import com.walkingrpg.shared.domain.walk.WalkSessionRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 天候がまだ付いていない散歩に、後付けで天候を確定させる（design.md §9・architecture.md §5「帰宅後」）。
 *
 * ```
 * 終了済みで session_weather が無いセッション
 *   → 代表点＋時刻を決める（WeatherQueryPlanner）
 *   → 設定されたプロバイダに問い合わせ
 *   → session_weather に保存（取れなければ行を作らない＝次回リトライ）
 * ```
 *
 * ## 呼び出し口は2つ、実装は1本
 *
 * - **散歩の終了時**：帰宅した直後なら通信が戻っていることが多いので、その場で埋める
 * - **アプリの起動時**：圏外・API障害で埋まらなかったぶんを後日埋める
 *   （issue #11 の完了条件「圏外で終了しても後日埋まる」）
 *
 * どちらも「未取得のセッションを全部見る」だけなので、セッションIDを引数に取らず
 * 1本にしてある。終了時の呼び出しも、直前に終わったセッションが未取得の1件として
 * 拾われるだけ＝**起動時の処理と完全に同じ経路**を通る。片方の経路にしか無いバグ、が生まれない。
 *
 * ## 冪等性
 * 何度呼んでも同じ状態に収束する：行がある（＝取得済み・確定済み）セッションは
 * そもそも対象に入らず、保存は1セッション1行の置き換え
 * （[SessionWeatherRepository.save]）。取れなかったセッションは**行を作らない**ので、
 * 「未取得」のまま次の実行に持ち越される。
 *
 * ## 実行は直列（[Mutex]）
 * 上の2つの呼び出し口は並行しうる（起動直後に散歩を畳んだ場合など）。
 * 「未取得を読む→問い合わせる→保存する」は不可分ではないので、並行に走ると
 * 両方が同じセッションを未取得と見なして**同じ問い合わせを二度投げる**。
 * 保存が置き換えなので状態は壊れないが、有料・レート制限付きのAPIを無駄に叩く。
 * そのため実行全体を [Mutex] で直列化する。後から来た実行は前の実行が
 * 保存し終えた状態を読むので、二重に投げない。
 *
 * これが効くのは**呼び出し側が同じインスタンスを使う**ときだけなので、
 * DI（`sharedModule`）では `single` で登録してある。
 *
 * ## 失敗の扱い
 * 1セッションの失敗で残りを止めない（1件の通信失敗が他の散歩を巻き込まない）。
 * 失敗したIDは [Result.unresolvedSessionIds] に入れて返すだけで、例外にはしない。
 * 例外にすると、呼び出し側（`AppViewModel`）が毎回それを握り潰す形になる。
 *
 * ## 諦めて確定させる場合
 * - **測位サンプルが1件も無いセッション**：位置が分からない＝訊く先が無い。
 *   サンプルは終了後に増えないので、待っても永遠に取れない
 * - **終了から [WeatherFetchConfig.giveUpAfterMs] 過ぎたセッション**：
 *   過去日の天候APIには取得可能期間の限界がある（[WeatherFetchConfig] のKDoc）
 *
 * どちらも [WeatherCondition.UNKNOWN] の行を作って確定させる。以降リトライ対象から外れ、
 * 変奏・条件判定からは除外される（architecture.md §8「天候APIの欠測」）。
 */
class FetchMissingSessionWeatherUseCase(
    private val sessionRepository: WalkSessionRepository,
    private val weatherRepository: SessionWeatherRepository,
    private val setupRepository: SetupRepository,
    private val providers: WeatherProviderSelector,
    private val clock: Clock,
    private val config: WeatherFetchConfig = WeatherFetchConfig.DEFAULT,
) {
    /** 実行を直列化する（上記「実行は直列」）。 */
    private val mutex = Mutex()

    suspend operator fun invoke(): Result = mutex.withLock { fetchMissing() }

    private suspend fun fetchMissing(): Result {
        val pending = weatherRepository.sessionIdsWithoutWeather()
        if (pending.isEmpty()) return Result()

        // 設定（＝セキュアストレージのAPIキー）を読むのは、埋める相手が居ると分かってから。
        val settings = setupRepository.loadWeatherSettings()
        val provider = providers.provider(settings.provider)
        val now = clock.nowMillis()

        val fetched = mutableListOf<SessionWeather>()
        val confirmedUnknown = mutableListOf<SessionWeather>()
        val unresolved = mutableListOf<Long>()
        var fetchCount = 0

        for (sessionId in pending) {
            // セッションが引けない＝この行はもう無い。天候だけ作っても宙に浮くので飛ばす。
            val session = sessionRepository.session(sessionId) ?: continue
            val endedAtMs = session.endedAtMs ?: continue

            val query = WeatherQueryPlanner.plan(sessionRepository.samples(sessionId))
            if (query == null) {
                confirmedUnknown += confirmUnknown(sessionId, now)
                continue
            }
            if (now - endedAtMs > config.giveUpAfterMs) {
                confirmedUnknown += confirmUnknown(sessionId, now)
                continue
            }

            // 上限に達しても諦め確定（通信しない）は続けたいので、ここで打ち切らず見送る。
            if (fetchCount >= config.maxFetchesPerRun) {
                unresolved += sessionId
                continue
            }
            fetchCount++

            val observation = try {
                provider.observe(query, settings.apiKey)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                // 圏外・API障害・キー未入力。行を作らずに次回へ持ち越す。
                unresolved += sessionId
                continue
            }

            val weather = SessionWeather(
                sessionId = sessionId,
                condition = observation.condition,
                temperatureCelsius = observation.temperatureCelsius,
                fetchedAtMs = now,
            )
            weatherRepository.save(weather)
            fetched += weather
        }

        return Result(
            fetched = fetched.toList(),
            confirmedUnknown = confirmedUnknown.toList(),
            unresolvedSessionIds = unresolved.toList(),
        )
    }

    private suspend fun confirmUnknown(sessionId: Long, nowMs: Long): SessionWeather {
        val weather = SessionWeather(
            sessionId = sessionId,
            condition = WeatherCondition.UNKNOWN,
            temperatureCelsius = null,
            fetchedAtMs = nowMs,
        )
        weatherRepository.save(weather)
        return weather
    }

    /**
     * @param fetched プロバイダから取れて保存したぶん。
     * @param confirmedUnknown 諦めて [WeatherCondition.UNKNOWN] で確定させたぶん。
     * @param unresolvedSessionIds 取れなかった・上限で見送ったぶん
     *  （行は作っていない＝次回リトライ対象）。
     */
    data class Result(
        val fetched: List<SessionWeather> = emptyList(),
        val confirmedUnknown: List<SessionWeather> = emptyList(),
        val unresolvedSessionIds: List<Long> = emptyList(),
    )
}

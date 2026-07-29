package com.walkingrpg.shared.data.weather

import com.walkingrpg.shared.data.db.WalkingRpgDatabase
import com.walkingrpg.shared.domain.weather.SessionWeather
import com.walkingrpg.shared.domain.weather.SessionWeatherRepository
import com.walkingrpg.shared.domain.weather.WeatherCondition
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [SessionWeatherRepository] のSQLDelight実装。
 *
 * ドメインモデル⇄DBスキーマの変換はこのクラスに閉じる（architecture.md §2）。
 * 保存はセッションIDを主キーにした `INSERT OR REPLACE`（SessionWeather.sq）＝
 * 同じセッションを何度取り込んでも1行。
 */
internal class SessionWeatherRepositoryImpl(
    private val database: WalkingRpgDatabase,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : SessionWeatherRepository {

    private val weathers get() = database.sessionWeatherQueries

    override suspend fun save(weather: SessionWeather): Unit = withContext(dispatcher) {
        weathers.upsertSessionWeather(
            session_id = weather.sessionId,
            condition = weather.condition.name,
            temperature = weather.temperatureCelsius,
            fetched_at = weather.fetchedAtMs,
        )
    }

    override suspend fun weather(sessionId: Long): SessionWeather? = withContext(dispatcher) {
        weathers.selectSessionWeather(sessionId).executeAsOneOrNull()?.let { row ->
            SessionWeather(
                sessionId = row.session_id,
                condition = row.condition.toCondition(),
                temperatureCelsius = row.temperature,
                fetchedAtMs = row.fetched_at,
            )
        }
    }

    override suspend fun sessionIdsWithoutWeather(): List<Long> = withContext(dispatcher) {
        weathers.selectSessionIdsWithoutWeather().executeAsList()
    }
}

/**
 * DBに入っている文字列 → ドメインのenum。
 *
 * 未知の値は [WeatherCondition.UNKNOWN] に落とす（分類を減らした・改名した後の
 * 古い行を読んでも落ちない）。行そのものは残るので、リトライ対象には戻らない
 * ＝「確定済みだが変奏には使えない」という扱いになり、意味としても合う。
 */
private fun String.toCondition(): WeatherCondition =
    WeatherCondition.entries.firstOrNull { it.name == this } ?: WeatherCondition.UNKNOWN

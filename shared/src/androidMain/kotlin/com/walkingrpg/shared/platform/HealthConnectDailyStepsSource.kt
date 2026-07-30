package com.walkingrpg.shared.platform

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.aggregate.AggregateMetric
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.walkingrpg.shared.domain.steps.CalendarDay
import com.walkingrpg.shared.domain.steps.DailySteps
import com.walkingrpg.shared.domain.steps.DailyStepsSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * Health Connect による歩数の取得（design.md §3「開始を押し忘れた日」）。
 *
 * **取れなければ黙って `null`**（[DailyStepsSource] の契約）。
 * Health Connect は「端末に入っていない・権限が無い・その日の記録が無い」が普通に起きる
 * 補助的な情報源なので、取れないこと自体は異常ではない。押し忘れ救済という「おまけ」の
 * 失敗で起動時の処理を巻き添えにしないため、ここで全部受け止めてログに残すだけにする
 * （[AndroidWalkNotifier] と同じ方針）。
 *
 * ## 権限のリクエスト導線は未実装（#18以降の別issue）
 *
 * 設定画面（#20）には載せなかった。位置情報（[AndroidLocationPermissionController]）の
 * 前例に倣うだけでは済まないため：
 *
 * - 付与状況の読み出しが **suspend**（`getGrantedPermissions`）なので、
 *   同期の `checkSelfPermission` を前提にした `LocationPermissionController` の形
 *   （`StateFlow` を素直に作れる）に乗らず、コントローラ側にスコープが必要になる
 * - 状態が2値（許可/拒否）では足りない。「Health Connect が入っていない・要更新・非対応」
 *   のときは権限ダイアログ自体を出せないので、ストアへ促す分岐まで持つことになる
 * - 権限の要求契約（`PermissionController.createRequestPermissionResultContract`）も
 *   位置情報とは別のランチャーで、`MainActivity` への預け入れが1本増える
 *
 * つまり expect/actual・ドメインのリポジトリ・UseCase を丸ごと1組増やす作業になり、
 * 「押し忘れ救済のおまけ」に対して重い。権限が無ければ `null` を返して何も起きない
 * （＝この状態でも他の機能は無傷）ので、独立したissueとして切り出す。
 */
internal class HealthConnectDailyStepsSource(
    private val context: Context,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : DailyStepsSource {

    override suspend fun dailySteps(day: CalendarDay): DailySteps? = withContext(dispatcher) {
        val client = client() ?: return@withContext null
        val granted = grantedPermissions(client)
        if (READ_STEPS !in granted) {
            Log.i(TAG, "歩数の読み取り権限が無いので取り込まない")
            return@withContext null
        }

        // 距離は無くても歩数だけで成立する（design.md §3 は「距離だけ拾い」だが、
        // 歩数計の距離は推定値なので、片方しか取れない端末で全部を諦める理由にはならない）。
        val metrics = buildSet<AggregateMetric<*>> {
            add(StepsRecord.COUNT_TOTAL)
            if (READ_DISTANCE in granted) add(DistanceRecord.DISTANCE_TOTAL)
        }

        val date = runCatching { LocalDate.parse(day.iso) }.getOrNull() ?: return@withContext null
        val result = runCatching {
            client.aggregate(
                AggregateRequest(
                    metrics = metrics,
                    // ローカル時刻での「その日の0時から翌日0時まで」。Instant で切ると
                    // 端末のタイムゾーンとずれて、日付をまたぐ歩数が隣の日に混ざる。
                    timeRangeFilter = TimeRangeFilter.between(
                        date.atStartOfDay(),
                        date.plusDays(1).atStartOfDay(),
                    ),
                ),
            )
        }.onFailure { Log.w(TAG, "歩数を集計できませんでした（$day）", it) }.getOrNull()
            ?: return@withContext null

        // その日の記録が1件も無ければ COUNT_TOTAL は null。0歩として保存はしない
        // （「歩いていない日」ではなく「分からない日」なので）。
        val steps = result[StepsRecord.COUNT_TOTAL] ?: return@withContext null

        DailySteps(
            day = day,
            steps = steps.toInt(),
            distanceEstimateMeters = result[DistanceRecord.DISTANCE_TOTAL]?.inMeters,
        )
    }

    /** Health Connect が使えない端末（未インストール・要更新・非対応）では `null`。 */
    private fun client(): HealthConnectClient? {
        val status = runCatching { HealthConnectClient.getSdkStatus(context) }
            .onFailure { Log.w(TAG, "Health Connect の状態を取得できませんでした", it) }
            .getOrNull()
        if (status != HealthConnectClient.SDK_AVAILABLE) {
            Log.i(TAG, "Health Connect が使えないので歩数を取り込まない（status=$status）")
            return null
        }
        return runCatching { HealthConnectClient.getOrCreate(context) }
            .onFailure { Log.w(TAG, "Health Connect に接続できませんでした", it) }
            .getOrNull()
    }

    private suspend fun grantedPermissions(client: HealthConnectClient): Set<String> =
        runCatching { client.permissionController.getGrantedPermissions() }
            .onFailure { Log.w(TAG, "Health Connect の権限を確認できませんでした", it) }
            .getOrDefault(emptySet())

    private companion object {
        const val TAG = "HealthConnectSteps"
        val READ_STEPS: String = HealthPermission.getReadPermission(StepsRecord::class)
        val READ_DISTANCE: String = HealthPermission.getReadPermission(DistanceRecord::class)
    }
}

package com.walkingrpg.shared.domain.llm

/**
 * 回線種別の境界（Android: `ConnectivityManager` / iOS: `NWPathMonitor`）。
 *
 * 事前バッチ（[PrebatchPoiFlavorUseCase]）を走らせてよい回線かを判定するためだけに置く。
 * 「Wi-Fiかどうか」ではなく「従量課金でないか」を訊く形にしているのは、テザリング・
 * 従量制Wi-Fi をOSが従量として申告するため（そこで数百リクエスト投げたら事故）。
 *
 * ## 置き場所がドメイン層な理由
 * platform層はドメインが定義したインターフェースを実装する側（CONTRIBUTING「依存の向き」）。
 * これを使うのはドメインのUseCaseなので、宣言はここに置き、実装（`AndroidNetworkStatus` /
 * `IosNetworkStatus`）をDIで注入する（歩数計の `DailyStepsSource` と同じ形）。
 */
interface NetworkStatus {

    /**
     * 従量課金でない回線に繋がっているか。
     *
     * **判定できないときは `false`**（＝生成しない側に倒す）。回線種別が取れないのは
     * 権限・OSの挙動・圏外のどれでも起きるが、どの場合も「たぶんWi-Fiだろう」と決めて
     * 課金と通信量を使うより、次の機会に見送るほうが安い。事前バッチは冪等なので、
     * 見送っても次の起動で続きから埋まる。
     */
    fun isUnmetered(): Boolean
}

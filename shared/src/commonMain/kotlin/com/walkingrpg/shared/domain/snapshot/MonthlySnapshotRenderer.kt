package com.walkingrpg.shared.domain.snapshot

/**
 * 1枚ぶんの絵をPNGバイト列にする境界。
 *
 * ## 実装がUI層（composeApp）にある唯一のポート
 * ドメインが定義したインターフェースをデータ層・プラットフォーム層が実装する
 * （architecture.md §2）のが基本だが、これだけは **UI層（`composeApp`）が実装する**。
 * 描画APIは Compose（`ImageBitmap` / `Canvas` / `CanvasDrawScope`）にしか無く、
 * `shared` は Compose に依存していない（依存させると「UIも共通化する」の意味が薄れ、
 * ドメインのテストがCompose入りになる）。DIの登録先も例外的に `AppModule` 側。
 *
 * 逆向きの依存にはなっていない：ドメインが知っているのはこの1枚のインターフェースだけで、
 * `composeApp` を名指ししない。段階（`GrowthStage`）から色を決めるのがUI層の責務
 * （`MapCanvas.stageColorHex`）という既存の分担そのままで、
 * **地図画面と同じ色で描かれること**がこの置き場所の見返り。
 */
interface MonthlySnapshotRenderer {

    /**
     * [scene] を描いてPNGのバイト列を返す。
     *
     * @param stats 絵に添える数値。今は月ラベル以外には使っていないが、
     *  「1枚を見れば数値も分かる」形にする余地を残すために渡す
     *  （数値の焼き込み先は `snapshot.stats_json` 側で、絵は読み物ではない）。
     * @return PNGのバイト列。失敗は例外で返す（呼び出し側が画像を書く前に落ちる＝
     *  行だけ残ってファイルが無い状態を作らない。`Snapshot.sq` のコメント）。
     */
    suspend fun render(scene: SnapshotScene, stats: MonthlySnapshotStats): ByteArray
}

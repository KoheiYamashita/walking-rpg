# 技術アーキテクチャ

概要設計は [design.md](design.md)。ここは実装側の設計を扱う。

最終更新: 2026-07-27

---

## 0. 方針

- **モバイルアプリのみ。サーバー実装なし。** LLM API等の外部SaaSは端末から直接呼ぶ
- **Kotlin Multiplatform + Compose Multiplatform。** UIも共通化する。例外は地図ビューだけ（ネイティブ埋め込み）
- **オフラインファースト。** 散歩中は通信ゼロで完結する。通信が必要なのはLLM生成と地図データ取得だけで、どちらも家で済む
- **MVVM + 単方向データフロー。** source of truth は歩行ログ。design.md §4.1「状態 = 歩行ログの累積、冪等で再計算できる」を、そのまま実装原則に昇格させる

## 1. 技術選定

| 領域 | 採用 | 理由 |
|---|---|---|
| 言語・共有 | Kotlin Multiplatform | ドメイン・データ層を100%共有 |
| UI | Compose Multiplatform | 画面数が少なく共通化の益が大きい。地図だけ例外 |
| 地図表示 | MapLibre Native（AndroidView / UIKitView 埋め込み）＋ ローカルPMTiles | サーバーなしでオフライン地図が成立。「実地図＋抽象レイヤー」はMapLibreのスタイルレイヤーで描く |
| DB | SQLDelight | KMP実績が長い。SQLファーストで型安全 |
| HTTP | Ktor Client | KMP標準。Anthropic APIを直接叩く |
| DI | Koin | KMP対応で軽量 |
| 非同期 | Coroutines + Flow | |
| ViewModel | androidx.lifecycle ViewModel（KMP対応版） | CMP公式サポート |
| 位置情報 | expect/actual：FusedLocationProvider / CoreLocation | |
| 歩数 | expect/actual：Health Connect / CMPedometer | 押し忘れ救済（design.md §3）専用 |
| 振動 | expect/actual：Vibrator / Core Haptics | 歩行中の唯一のフィードバックチャネル |

## 2. レイヤー構成（MVVM + UseCase）

```
┌─ UI層（composeApp）────────────────────────────┐
│  Screen (Composable) ←─ UiState ──  ViewModel  │
└───────────────┬────────────────────────────────┘
                ↓ UseCase 呼び出し
┌─ ドメイン層（shared/domain・純Kotlin）──────────┐
│  UseCase ／ ドメインモデル                       │
│  成長・図鑑・謎チェーン・map matching のロジック  │
│  Repository インターフェース                     │
└───────────────┬────────────────────────────────┘
                ↓ 実装
┌─ データ層（shared/data）───────────────────────┐
│  Repository 実装 ／ SQLDelight ／ Ktor（LLM）    │
└───────────────┬────────────────────────────────┘
                ↓ 注入
   Platform層（shared/platform・expect/actual）
   Location / Pedometer / Haptics / SessionKeeper
```

### 役割規約（ここを守ればMVVMが崩れない）

- **Composable**：描画とイベント送出のみ。状態を持たない（`remember` はUI都合の一時状態だけ）
- **ViewModel**：`StateFlow<UiState>` の組み立てとUseCase呼び出しのみ。プラットフォームAPI・DB・HTTPに触らない
- **UseCase**：1操作1クラス、純Kotlin。時刻・乱数は使わず `Clock` 等を注入（テスト可能性の担保）
- **Repository**：永続化と外部APIの境界。ドメインモデル⇄DBスキーマの変換はデータ層に閉じる
- **expect/actual**：インターフェース越しにDIで注入。ドメイン層からプラットフォームは見えない

## 3. モジュール構成

個人開発規模なので分割は最小限に：

```
composeApp/   # CMP UI・ViewModel・ナビゲーション・地図埋め込み
shared/
  domain/     # 純Kotlin。外部依存なし（テストの主戦場）
  data/       # SQLDelight・Ktor・Repository実装
  platform/   # expect/actual
iosApp/       # iOSエントリポイント
```

## 4. データモデル（初版スケッチ）

### 真実の源（イベント系。絶対に消さない）

- `walk_session(id, started_at, ended_at, end_reason)`
- `location_sample(session_id, ts, lat, lon, accuracy)`
- `passage(session_id, way_id, ts)` — map matching の結果。導出だが再計算コストが高いので永続化（ソースは sample）
- `step_import(date, steps, distance_estimate)` — 押し忘れ救済

### マスタ（OSM取り込み）

- `way(id, name?, highway, geometry, length_m)`
- `poi(id, kind, tags, lat, lon)` — 図鑑素材・配置候補。安全フィルタ（design.md §6）適用済みのものだけ入れる

### 導出（キャッシュ。いつでも捨てて再計算できる）

- `way_growth(way_id, pass_count, stage, branch_attr)`
- `codex_progress(species_id, visit_count, foreshadow_stage, discovered_at?)`

### シナリオ・謎

- `arc(id, genre, difficulty, skeleton_json, created_at)` — 骨格一括生成の固定先
- `episode(arc_id, index, state, ending_lineage?)`
- `chain_step(episode_id, index, target_ref, condition, unlocked_at?, resolved_at?)`
- `fragment(chain_step_id, text)` — 現地で出す1〜2文

### LLM

- `llm_cache(key, kind, prompt_hash, text, created_at)`
- `utterance_log(place_ref, angle, said_at)` — 単調化対策（同じ切り口の再使用禁止。design.md §5）

> **原則：導出テーブルはすべて `passage` から再計算できること。**
> ロジック変更やマイグレーションを恐れない。これが減衰なし設計の実装上の配当。

## 5. 主要フロー

### 散歩セッション中（完全オフライン）

```
GPS（1〜3秒間隔）→ 精度フィルタ → map matching（ローカルway表と照合）
  → passage 記録 → 成長・図鑑・チェーン判定（純関数）
  → イベント発生なら振動1回（レート制限：1散歩2〜3回）
```

- Android：Foreground Service で画面オフでも継続
- iOS：**セッション中のみ** background location（常時測位はしない＝測位方針の決定と一致）

### 帰宅後

```
自宅ジオフェンス＋停止検知 → セッション終了 → 集計（純関数）
  → LLM生成キューへ投入（振り返り文・パートナーの一言・次のヒント文）
```

- **数値は即時、文章は遅延OK** を原則にする。圏外・API障害でも振り返り画面は数値サマリだけで成立し、文章は生成でき次第差し込む

### LLM運用（サーバーなし）

- Ktor で Anthropic API を直接呼ぶ。モデルはHaiku級（design.md §7）
- APIキーは自分のものを端末のセキュアストレージに保存（**個人アプリの前提**）
- **他人に配布するならこの方式は使えない**（キー抜き取りリスク）。その時は薄いプロキシを立てる。サーバーなし前提の唯一の破れ目としてここに明記しておく
- 生成はすべて非同期バッチ。Wi-Fi接続時に地点フレーバーを事前生成（design.md §7と同じ）

### OSMデータ取り込み

- 初回セットアップで対象圏（500m〜1km）を Overpass API から取得してSQLiteへ
- 地図タイルは対象圏のPMTilesを1回ダウンロードしてローカル参照
- 以後の散歩は圏外でも完全に動く

## 6. テスト方針

- ドメイン層は純Kotlinなので通常のunit testで全部書ける。map matching・成長・チェーン判定にテストを集中させる
- **冪等性テスト**：同じ `passage` 列 → 必ず同じ導出状態、を常に確認する（この設計の背骨）
- **GPSリプレイテスト**：実際の散歩の `location_sample` をフィクスチャ化し、ロジック変更のたびに回帰させる

## 7. 既知の制約・リスク

| 項目 | 内容 |
|---|---|
| iOSのバックグラウンド測位 | セッション中限定とはいえ、画面オフでの挙動確認が**最優先の技術検証項目** |
| 地図のCMP非対応 | 共通化できない唯一の部品。expect/actual の薄いラッパで隔離する |
| APIキー | 個人利用限定の割り切り。配布時はプロキシ必須 |
| Health Connect / HealthKit | 押し忘れ救済のみに使用。権限が取れない場合は機能ごと落とせる設計にする |

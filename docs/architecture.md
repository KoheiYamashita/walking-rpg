# 技術アーキテクチャ

概要設計は [design.md](design.md)。ここは実装側の設計を扱う。

最終更新: 2026-07-30

---

## 0. 方針

- **モバイルアプリのみ。サーバー実装なし。** LLM API等の外部SaaSは端末から直接呼ぶ
- **Kotlin Multiplatform + Compose Multiplatform。** UIも共通化する。例外は地図ビューだけ（ネイティブ埋め込み）
- **オフラインファースト（ゲームロジック限定）。** 記録・成長・判定は通信なしで成立する（通信に**依存しない**）。
  地図タイルはオンライン取得（圏外はキャッシュで劣化許容）。
  プレイ中のLLM呼び出しは贅沢品として許可するが、失敗しても事前生成分と定型文で必ず成立する
- **MVVM + 単方向データフロー。** source of truth は歩行ログ。design.md §4.1「状態 = 歩行ログの累積、冪等で再計算できる」を、そのまま実装原則に昇格させる

## 1. 技術選定

| 領域 | 採用 | 理由 |
|---|---|---|
| 言語・共有 | Kotlin Multiplatform | ドメイン・データ層を100%共有 |
| UI | Compose Multiplatform | 画面数が少なく共通化の益が大きい。地図だけ例外 |
| 地図表示 | MapLibre Native（AndroidView / UIKitView 埋め込み）＋ **OpenFreeMap**（オンラインタイル、キー不要） | 常に最新のOSM背景。「実地図＋抽象レイヤー」はMapLibreのスタイルレイヤーで描く。圏外はキャッシュで劣化許容 |
| DB | SQLDelight | KMP実績が長い。SQLファーストで型安全 |
| HTTP | Ktor Client | KMP標準。Anthropic APIを直接叩く |
| DI | Koin | KMP対応で軽量 |
| 非同期 | Coroutines + Flow | |
| ViewModel | androidx.lifecycle ViewModel（KMP対応版） | CMP公式サポート |
| 位置情報 | expect/actual：FusedLocationProvider / CoreLocation | |
| 歩数 | expect/actual：Health Connect / CMPedometer | 押し忘れ救済（design.md §3）専用 |
| 振動 | expect/actual：Vibrator / Core Haptics | 歩行中の唯一のフィードバックチャネル |
| 天候 | `WeatherProvider`抽象＋3実装（Open-Meteo / OpenWeatherMap / Visual Crossing） | 設定画面で選択。Open-Meteoはキー不要。帰宅後にタイムスタンプ＋位置で後付け取得 |
| LLM | `LlmClient`抽象＋**2フォーマット実装**（Anthropic Messages / OpenAI Chat Completions） | ベースURL・モデル名・キーは自由設定。OpenAI互換エンドポイントに対応 |
| 設定・キー保管 | expect/actual：Keystore / Keychain | **各種APIキーはユーザーが設定画面から入力**して保存 |

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
- `session_weather(session_id, condition, temperature, fetched_at)` — 帰宅後に天候APIで後付け確定。
  取得失敗は次回起動時にリトライ。欠測は「天候不明」として変奏・条件判定から除外
- `snapshot(month, image_path, stats_json, created_at)` — 月次スナップショット（耐久コアの筆頭）

### マスタ（OSM取り込み）

- `way(id, name?, highway, geometry, length_m)`
- `poi(id, kind, tags, lat, lon)` — 図鑑素材・配置候補。安全フィルタ（design.md §6）適用済みのものだけ入れる

### 導出（キャッシュ。いつでも捨てて再計算できる）

- `way_growth(way_id, pass_count, stage, branch_attr)` — `pass_count` は**通過ごと**に数える
  （design.md §4.1）
- `codex_progress(species_id, visit_count, foreshadow_stage, discovered_at?)` —
  `visit_count` は**セッション単位**で数える（design.md §4.4「同じ川に10回通って」＝10回の散歩)。
  正確には「その種のカテゴリに属するPOIのうち、いちばん通ったPOIの近傍を1本以上通った散歩の数」。
  カテゴリ内で合算せずPOI単位の最大値を採るのは、合算にすると「近所の公園を1回ずつ」でも
  「同じ公園に通い詰め」でも同じ数になり、design.md §4.4 の中核メカニクスが消えるため。
  POIとwayの対応（空間結合）はテーブルに持たず**毎回その場で計算する**
  （POI数百件 × way約220本の純計算。`poi` / `way` はマスタ再取り込みで作り直されるので、
  対応表を持つと古いIDを指した行が残る）。`discovered_at` は端末時計ではなく
  **閾値に到達したセッションの `passage.ts`** から導く（読んだ瞬間に導出が冪等でなくなる）

### シナリオ・謎

- `arc(id, genre, difficulty, skeleton_json, created_at)` — 骨格一括生成の固定先
- `episode(arc_id, index, state, ending_lineage?)`
- `chain_step(episode_id, index, target_ref, condition, unlocked_at?, resolved_at?)`
- `fragment(chain_step_id, text)` — 現地で出す1〜2文

### LLM

- `llm_cache(key, kind, prompt_hash, text, created_at)` — 導出キャッシュ（捨てて作り直せる）
- `utterance_log(id, session_id, place_ref, angle, said_at)` — 単調化対策
  （同じ切り口の再使用禁止。design.md §5）。**真実の源に準ずる追記ログ**で、
  `passage` からは再計算できない（「何を言ったか」はログに残っていない）。
  だから消せない＝バックアップの対象（§6）。
  - `place_ref` は内部キー（`way:<id>` / `species:<id>` / `day`）で、
    **プロンプトには絶対に出さない**（一言に位置情報を渡さない規約）
  - `angle` は切り口（`RemarkAngle` の name）。1回の散歩につき1行で、
    生成が成功したときだけ書く（定型文で凌いだ散歩は書かない）
  - `said_at` は端末時計ではなく **`walk_session.started_at` から導く**
    （`codex_progress.discovered_at` と同じ理由）。一言の読み出し側は同じ事実から
    `prompt_hash` を計算し直してキャッシュと突き合わせるので、時計を読むと
    「同じ散歩なのに時間が経つと指紋が変わる」＝毎回再生成（＝課金）になる

> **原則：導出テーブルはすべて `passage` から再計算できること。**
> ロジック変更やマイグレーションを恐れない。これが減衰なし設計の実装上の配当。

### スキーマの更新

「恐れない」の前提として、**既存DBがスキーマに追従する経路を常に用意しておく**。
新規インストールは `.sq` から作られるが、既に `walking_rpg.db` を持っている端末は
`user_version` の差分ぶんの `.sqm` しか流さないので、`.sq` を書き換えるだけでは
新しいテーブルが作られない。

- バージョンの起点は v1 ＝ スパイク期のスキーマ（`walk_session` / `location_sample`）。
  それ以前の変遷は再現しない（スパイク期は再インストール運用だった）
- 各バージョンのスキーマ実体（`databases/*.db`）はコミットし、`verifyMigrations` で
  `.sq` と `.db + .sqm` の食い違いをビルドで落とす
- 真実の源はマイグレーションで壊さない。導出テーブルは作り直して再計算すればよい

手順は [CONTRIBUTING.md「DBマイグレーション（SQLDelight）」](../CONTRIBUTING.md#dbマイグレーションsqldelight)。

## 5. 主要フロー

### 散歩セッション中（完全オフライン）

```
GPS（1〜3秒間隔）→ 精度フィルタ → map matching（ローカルway表と照合）
  → passage 記録 → 成長・図鑑・チェーン判定（純関数）
  → イベント発生なら振動1回（レート制限：1散歩2〜3回）
```

- **基本は画面ON・アプリフォアグラウンド**（KEEP_SCREEN_ONで消灯を抑止）。design.md §3の決定
- Android：Foreground Service は**保険**（誤って画面オフ・ロックされても記録が途切れないため）
- iOS：フォアグラウンド動作＋idle timer無効化。バックグラウンド測位はセッション中の保険のみ
  （常時測位はしない＝測位方針の決定と一致）

### 帰宅後

```
自宅ジオフェンス＋停止検知 → セッション終了 → 集計（純関数）
  → 天候の後付け取得（タイムスタンプ＋位置で天候APIへ。失敗は次回リトライ）
  → LLM生成キューへ投入（振り返り文・パートナーの一言・次のヒント文）
```

- **数値は即時、文章は遅延OK** を原則にする。圏外・API障害でも振り返り画面は数値サマリだけで成立し、文章は生成でき次第差し込む

### LLM運用（サーバーなし）

- LLMクライアントは`LlmClient`インターフェース1枚の背後に**2フォーマット実装**：
  Anthropic Messages API / OpenAI Chat Completions。ドメイン層はフォーマットの違いを知らない
- **ベースURL・モデル名・キーは設定で自由**。OpenAI互換エンドポイント（OpenRouter・ローカルLLM等）が
  そのまま使える。既定はAnthropic + Haiku級（design.md §7）
- **接続設定（フォーマット・URL・モデル・キー）は設定画面からユーザーが入力（必須）**し、
  キーはKeystore / Keychainに保存。アプリ本体にキーを埋め込まない。
  **初回セットアップで疎通確認が通るまでプレイを開始できない**
  （散歩中のLLM呼び出し失敗時に定型文で凌ぐランタイムの縮退は従来どおり。未設定とは別の話）
- **他人に配布するならこの方式は使えない**（キー抜き取りリスク）。その時は薄いプロキシを立てる。サーバーなし前提の唯一の破れ目としてここに明記しておく
- 生成タイミングは3段階（design.md §7）。実装上の対応：
  1. **骨格一括**：アーク開始時に生成し `arc.skeleton_json` に固定
  2. **話の肉付け**：前話の分岐確定イベントでキュー投入（振り返り〜次の出発前に完了）
  3. **フロンティア先読み**：出発時に「次に到達しうる全地点」の `fragment` を生成・キャッシュ。
     未生成のまま到達した場合は定型文＋後追い生成で補う
- 散歩中の即興生成（外れ地点フレーバー等）は fire-and-forget。タイムアウト短め、失敗は無視
- 地点の基本フレーバーはWi-Fi時の事前バッチ（design.md §7と同じ）

### OSMデータ取り込み

- 初回セットアップで対象圏（500m〜1km）を Overpass API から取得してSQLiteへ
- 地図タイルはOpenFreeMapからオンライン取得（MapLibreのキャッシュにより一度表示した範囲は圏外でも出る）
- ゲームロジック（記録・成長・判定）は圏外でも完全に動く

## 6. バックアップ（必須要件）

「街の成長・図鑑・スナップショットは絶対に消さない」（design.md §6）の単一障害点は端末本体。
サーバーなしのまま2段構えで潰す：

- **OS自動バックアップ**：DBファイルとスナップショット画像を
  Android Auto Backup / iCloudバックアップの対象に含める。
  APIキーはセキュアストレージ側なのでバックアップに乗らない（＝安全）
- **手動エクスポート/インポート**：真実の源（walk_session / location_sample / passage /
  step_import / session_weather）＋**utterance_log**（再計算できない追記ログ。§4）
  ＋スナップショット画像＋シナリオ進行をzipで書き出し・取り込み。
  導出テーブルは復元後に再計算する（冪等性がここで効く）
- 機種変更はエクスポート→インポートで完結。**サーバー同期は作らない**

## 7. テスト方針

- ドメイン層は純Kotlinなので通常のunit testで全部書ける。map matching・成長・チェーン判定にテストを集中させる
- **冪等性テスト**：同じ `passage` 列 → 必ず同じ導出状態、を常に確認する（この設計の背骨）
- **GPSリプレイテスト**：実際の散歩の `location_sample` をフィクスチャ化し、ロジック変更のたびに回帰させる

## 8. 既知の制約・リスク

| 項目 | 内容 |
|---|---|
| 画面ON運用のバッテリー | 30分の画面ON＋GPS連続取得の消費実測が最優先の技術検証項目（#2） |
| 画面オフ時の記録欠落 | 基本は画面ONだが、誤ロック時の保険（Foreground Service / background location）の挙動を副次確認 |
| 地図のCMP非対応 | 共通化できない唯一の部品。expect/actual の薄いラッパで隔離する |
| APIキー | ユーザー入力方式でも、配布するなら入力の手間がオンボーディングの壁になる。配布時はプロキシ必須 |
| Health Connect / HealthKit | 押し忘れ救済のみに使用。権限が取れない場合は機能ごと落とせる設計にする |
| 天候APIの欠測 | 後付け取得なのでリトライで埋める。欠測は「天候不明」として変奏・条件判定から除外 |

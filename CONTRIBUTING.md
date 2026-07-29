# 開発ガイド

このリポジトリの実装規約。設計の根拠は [docs/architecture.md](docs/architecture.md) を参照。

## モジュール構成（architecture.md §3）

```
composeApp/   # CMP UI・ViewModel・ナビゲーション・地図埋め込み
shared/
  domain/     # 純Kotlin。外部依存なし（テストの主戦場）
  data/       # SQLDelight・Ktor・Repository実装
  platform/   # expect/actual
iosApp/       # iOSエントリポイント（Xcodeプロジェクト）
```

`shared` の3パッケージは `shared/src/commonMain/kotlin/com/walkingrpg/shared/` 配下の
`domain` / `data` / `platform` に対応する。

## MVVMレイヤー規約（architecture.md §2「役割規約」の転記）

> ここを守ればMVVMが崩れない。

- **Composable**：描画とイベント送出のみ。状態を持たない（`remember` はUI都合の一時状態だけ）
- **ViewModel**：`StateFlow<UiState>` の組み立てとUseCase呼び出しのみ。プラットフォームAPI・DB・HTTPに触らない
- **UseCase**：1操作1クラス、純Kotlin。時刻・乱数は使わず `Clock` 等を注入（テスト可能性の担保）
- **Repository**：永続化と外部APIの境界。ドメインモデル⇄DBスキーマの変換はデータ層に閉じる
- **expect/actual**：インターフェース越しにDIで注入。ドメイン層からプラットフォームは見えない

### 依存の向き

```
UI層（composeApp）→ ドメイン層（shared/domain）← データ層（shared/data）
                                              ← プラットフォーム層（shared/platform）
```

ドメイン層は誰にも依存しない。データ層・プラットフォーム層は
ドメイン層が定義したインターフェースを実装し、DI（Koin）で注入する。

### 実装例（雛形）

- `composeApp/.../ui/home/HomeScreen.kt` — 状態を持たないComposable。`HomeContent` に描画を分離
- `composeApp/.../ui/home/HomeViewModel.kt` — `StateFlow<HomeUiState>` の組み立てのみ
- `composeApp/.../di/AppModule.kt` — ViewModelのDI定義とKoin初期化
- `shared/.../di/SharedModule.kt` — UseCase・Repository・platform実装のDI定義

新しい画面はこの1組をコピーして作る。

## 状態設計の原則（architecture.md §0 / design.md §4.1）

- **source of truth は歩行ログ**。`状態 = 歩行ログの累積`
- 導出テーブル（成長・図鑑進捗など）はすべて `passage` から再計算できること
- 同じ `passage` 列からは必ず同じ導出状態が出る（冪等性テストで常に確認する）

## DBマイグレーション（SQLDelight）

**アンインストールを前提にしない。** `.sq` を書き換えただけでは、既に
`walking_rpg.db` を持っている端末に新しいテーブルは作られない
（ドライバは `user_version` の差分ぶんの `.sqm` しか流さない）。

配置は `shared/src/commonMain/sqldelight/` 配下：

```
com/walkingrpg/shared/data/db/*.sq    # 現行スキーマ＋クエリ（新規インストールはこれで作られる）
com/walkingrpg/shared/data/db/N.sqm   # v N → v N+1 の差分（既存DBはこれを順に流して追いつく）
databases/N.db                        # 各バージョンのスキーマ実体（コミットする）
```

- **バージョンの起点は v1 ＝ スパイク期のスキーマ**（`walk_session` / `location_sample`）。
  それ以前の変遷は再現しない（スパイク期は再インストール運用だった）。
  スキーマバージョンは `.sqm` の本数から自動で決まる（`N.sqm` があれば version = N+1）
- **`databases/*.db` はコミットする。** これが無いと `verifyMigrations` が検証できない
  （バイナリだがスキーマだけで数十KB、歩行データは入らない）
- `verifyMigrations = true`（`shared/build.gradle.kts`）なので、`.sq` と `.db + .sqm` が
  食い違うと `check` で落ちる。**テーブルを足して `.sqm` を書き忘れると端末ではなくビルドで気付く**

### テーブル・列を足すとき

1. `.sq` を編集する（新規インストール向けの正しい形）
2. 現在の version と同じ番号の `.sqm` を追加する（version 2 なら `2.sqm`）。
   `.sq` の `CREATE` 文と同じ内容を書く
3. 新しいバージョンの `.db` を生成してコミットする
   ```bash
   ./gradlew :shared:generateCommonMainWalkingRpgDatabaseSchema
   ```
4. 検証する
   ```bash
   ./gradlew :shared:verifySqlDelightMigration   # .sq と .db + .sqm の一致
   ./gradlew :shared:testDebugUnitTest           # DatabaseMigrationTest が実DBで往復を確認
   ```

導出テーブル（`way_growth` など）は捨てて再計算できるので、列を足すときも
`.sqm` では素直に `ALTER TABLE` / 作り直しでよい。値の移し替えは要らない
（再計算すれば埋まる）。真実の源（`walk_session` / `location_sample` / `passage`）は
マイグレーションで壊さないこと。

## ビルド

必要環境：JDK 17、Android SDK（compileSdk 36）。iOSビルドはmacOS + Xcodeが必要。

```bash
./gradlew :composeApp:assembleDebug     # Android デバッグAPK
./gradlew :composeApp:installDebug      # 実機・エミュレータへインストール
./gradlew allTests                      # 共通テスト
open iosApp/iosApp.xcodeproj            # iOS（macOSのみ）
```

Android SDKの場所は `local.properties`（Git管理外）か `ANDROID_HOME` で指定する。

地図タイルはOpenFreeMapからオンライン取得するので準備は要らない（キー不要）。

## 位置情報の扱い

**ユーザー固有の座標（自宅・対象圏）をリポジトリに持ち込む経路を作らない。**
設定ファイルから初期位置を読む仕組みも置かない（書いた座標が事故でコミットされうるため）。

- 地図が特定の場所に寄るのは、**位置情報の権限を許可して現在地が取れたときだけ**。
  取れない間は国全体が見える広域表示（`MapCamera.WIDE_DEFAULT`）
- OSMマスタの取り込み範囲（対象圏）の中心も**現在地のみ**。取れなければエラーにする
- テストのフィクスチャは**架空座標**で作る（実在の座標・地名をコード・テスト・
  コメントに書かない）
- `docs/local/` は位置情報を含むローカルメモ。**絶対にコミットしない**（.gitignore済み）

## テスト方針（architecture.md §7）

- ドメイン層は純Kotlinなので通常のunit testで書ける。map matching・成長・チェーン判定に集中させる
- **冪等性テスト**：同じ `passage` 列 → 必ず同じ導出状態
- **GPSリプレイテスト**：実際の散歩の `location_sample` をフィクスチャ化して回帰させる

## 依存の追加

バージョンは必ず `gradle/libs.versions.toml`（バージョンカタログ）で管理し、
モジュール側では `libs.xxx` 参照だけを書く。直接の座標指定は避ける。

## コミット・ブランチ

- ブランチ：`feature/<issue番号>-<短い説明>`
- コミットメッセージは日本語可。Conventional Commits（`feat:` / `fix:` / `docs:` / `chore:`）を使う
- `docs/local/` は位置情報を含むローカルメモ。**絶対にコミットしない**（.gitignore済み）

# scripts

## generate-tiles.sh — 対象圏のPMTiles生成

オフライン地図（design.md §9 / architecture.md §1）に使うベクタタイルを、
Protomapsの公開日次ビルドから対象圏だけ切り出す。

サーバーを持たない方針なので、生成した `.pmtiles` は
`composeApp/src/androidMain/assets/map/` に置いてAPKへ同梱する。

### プライバシー上の約束

**対象圏の座標・地名はリポジトリに入れない。**

- bboxは `scripts/local-config`（`.gitignore` 済み）か `--bbox` 引数から渡す
- 生成された `*.pmtiles` も `.gitignore` 済み
- 地図の初期表示位置も `local.properties`（`.gitignore` 済み）から読む
- スクリプトはbboxを標準出力に出さない

### 準備

1. `pmtiles` CLI（[go-pmtiles](https://github.com/protomaps/go-pmtiles/releases)）を入れる

   ```bash
   pmtiles version
   ```

2. ローカル設定を作る

   ```bash
   cp scripts/local-config.example scripts/local-config
   $EDITOR scripts/local-config      # BBOX を自分の対象圏に書き換える
   ```

   bboxは [bboxfinder.com](http://bboxfinder.com) などで `W,S,E,N` の順に取れる。
   1日30分＝2km圏（design.md §11）を想定すると、一辺3〜4kmもあれば十分。

### 生成

```bash
scripts/generate-tiles.sh
```

引数で上書きもできる（ローカル設定より優先）：

```bash
scripts/generate-tiles.sh --bbox=W,S,E,N --maxzoom=15 --out=/tmp/area.pmtiles
```

planet全体（100GB超）はダウンロードされない。`pmtiles extract` が
HTTPレンジリクエストで必要なタイルだけ取ってくる。
z15・一辺4km程度なら数MB〜数十MBに収まる。

### 生成後

1. 出力先は既定で `composeApp/src/androidMain/assets/map/area.pmtiles`。
   ファイル名は自由（アプリは `assets/map/` 配下の最初の `.pmtiles` を使う）
2. スクリプトが最後に出力する3行を `local.properties` に貼る

   ```properties
   map.center.lat=...
   map.center.lon=...
   map.zoom=15
   ```

3. ビルドして起動 → ホーム画面の「地図を見る」

   ```bash
   ./gradlew :composeApp:installDebug
   ```

### 表示のしくみ

MapLibre Native は `pmtiles://` プロトコルに対応している（Android 11.8.0以降）。
ただしAPKのassetsは**レンジ読み出しができない**ため、初回起動時に
assets → 内部ストレージ（`filesDir/map/`）へコピーし、
`pmtiles://file://<絶対パス>` として参照している
（`shared/.../platform/map/AndroidLocalMapSource.kt`）。

スタイルは `composeApp/src/androidMain/assets/map/style-local.json`。
文字（symbolレイヤー）を持たないのでグリフの取得が発生せず、完全にオフラインで完結する。

### ライセンス

生成物はOpenStreetMapのODbLに従う（`© OpenStreetMap contributors`）。
配布する場合はアプリ内に帰属表示が必要。

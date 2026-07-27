# assets/map

Android版のローカル地図素材を置く場所。

| ファイル | 用途 | Git管理 |
|---|---|---|
| `style-local.json` | 最小スタイル（背景・陸・水域・建物・道路）。`{{PMTILES_URL}}` は起動時に実パスへ置換される | する |
| `*.pmtiles` | 対象圏のベクタタイル | **しない**（`.gitignore` 済み） |

`.pmtiles` は `scripts/generate-tiles.sh` で自分の対象圏を切り出してここに置く。
手順は [scripts/README.md](../../../../../scripts/README.md) を参照。

置かなくてもビルドは通る（地図画面が「タイルがありません」と表示するだけ）。
アプリは `assets/map/` 配下で最初に見つかった `.pmtiles` を採用するので、
ファイル名は自由（＝実在の地名をファイル名としてコミットする必要がない）。

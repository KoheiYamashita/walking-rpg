# GPSリプレイテストのフィクスチャ

実際に歩いたログ（`location_sample`）を固定して、map matching のロジック変更のたびに
回帰させるための仕組み（architecture.md §7）。

## 置き場所

`GpsReplayFixtures.kt`（このディレクトリ）にJSON文字列として並べ、`ALL` に載せる。
テスト（`MapMatchingReplayTest`）は `ALL` を全件流す。

`.json` ファイルではなくKotlinの文字列にしているのは、**commonTest（KMP）に移植可能な
リソース読み込みの手段が無い**ため（JVMの `getResource` はNativeターゲットで使えない）。
中身は素のJSONなので、エディタで貼り付ければそのまま使える。

## JSONスキーマ（`schemaVersion: 1`）

```jsonc
{
  "schemaVersion": 1,
  "name": "2026-08-01-morning",     // 失敗時の識別名
  "note": "駅前を一周",              // 何のログかのメモ
  "ways": [                          // そのとき使ったwayマスタ
    {
      "id": 101,                     // OSMのway ID
      "name": "○○通り",              // null可
      "highway": "residential",
      "geometry": "lat,lon;lat,lon"  // DBの way.geometry と同じ形式（WayGeometryCodec）
    }
  ],
  "session": {                       // アプリのエクスポートJSONをそのまま貼る
    "schemaVersion": 1,
    "sessionId": 1,
    "startedAt": 1767225600000,
    "endedAt": 1767225875000,
    "endReason": "MANUAL",
    "sampleCount": 56,
    "samples": [
      { "ts": 1767225600000, "lat": 35.7, "lon": 139.7, "accuracy": 9.0 }
    ]
  },
  "expectedPassages": [              // 目視で確認した正解（時刻順）
    { "wayId": 101, "ts": 1767225600000 }
  ]
}
```

`session` は `WalkSessionExporterImpl` が出力するJSONと同じ形。読み込み側は
未知のフィールドを無視するので、エクスポート側にフィールドが増えても壊れない。

## 実散歩のログを追加する手順

1. アプリのセッション一覧からエクスポートしてJSONを取り出す
2. そのときのwayマスタ（`selectAllWays` の結果）を `ways` に写す
3. `session` にエクスポートJSONを貼り、`expectedPassages` を**地図で目視確認しながら**書く
   （issue #8 の完了条件は「実際に歩いた道が正しくpassage化される」の目視確認）
4. `GpsReplayFixtures.ALL` に追加してテストを回す

**位置情報の扱い**：実データには自宅を含む実際の位置が入る。リポジトリに入れるものは、
自宅から離れた区間だけを切り出す・座標を平行移動するなど、匿名化してから貼ること
（座標を平行移動しても、wayマスタ側を同じだけ動かせばmatchingの結果は変わらない）。

## テストが落ちたときは

閾値（`MapMatchingConfig`）やアルゴリズムを意図的に変えたなら、直すのは
`expectedPassages` の方。**目視で正しいと確認してから**更新すること。
そうでなければ、ロジックの退行を疑う。

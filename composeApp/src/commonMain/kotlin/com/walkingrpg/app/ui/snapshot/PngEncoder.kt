package com.walkingrpg.app.ui.snapshot

import androidx.compose.ui.graphics.ImageBitmap

/**
 * 描いた [ImageBitmap] をPNGのバイト列にする。
 *
 * Composeの `ImageBitmap` は共通の型だが、**エンコーダは共通に無い**
 * （中身は Android なら `android.graphics.Bitmap`、iOS なら Skia の `Bitmap`）。
 * `shared/platform` ではなく composeApp の expect/actual にしてあるのは、
 * 引数が Compose の型で、`shared` は Compose に依存していないから
 * （`MonthlySnapshotRenderer` のKDoc）。
 *
 * PNGを選ぶ理由：抽象レイヤーは単色の線の集まりなので、可逆圧縮でも小さく収まる
 * （JPEGだと色境界に滲みが出るうえ、永久保存する絵を劣化させる意味がない）。
 */
internal expect fun ImageBitmap.encodeToPng(): ByteArray

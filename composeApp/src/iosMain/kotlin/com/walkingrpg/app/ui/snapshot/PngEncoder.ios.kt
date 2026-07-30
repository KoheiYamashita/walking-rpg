package com.walkingrpg.app.ui.snapshot

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

/**
 * iOSはSkia（CMPのレンダラそのもの）でエンコードする。
 *
 * `UIImage` 経由（`UIImagePNGRepresentation`）にしないのは、`ImageBitmap` から
 * `UIImage` へ行くのに結局Skia側を1回通ることになり、変換が1段増えるだけだから。
 */
internal actual fun ImageBitmap.encodeToPng(): ByteArray {
    val image = Image.makeFromBitmap(asSkiaBitmap())
    val data = image.encodeToData(EncodedImageFormat.PNG)
    checkNotNull(data) { "PNGへの変換に失敗しました" }
    return data.bytes
}

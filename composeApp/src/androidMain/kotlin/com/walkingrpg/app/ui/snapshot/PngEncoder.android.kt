package com.walkingrpg.app.ui.snapshot

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import java.io.ByteArrayOutputStream

/**
 * Androidは `android.graphics.Bitmap.compress`。
 *
 * 品質引数（100）はPNGでは無視される（可逆圧縮なので）が、APIが要求するので渡す。
 */
internal actual fun ImageBitmap.encodeToPng(): ByteArray {
    val stream = ByteArrayOutputStream()
    val compressed = asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, stream)
    check(compressed) { "PNGへの変換に失敗しました" }
    return stream.toByteArray()
}

private const val PNG_QUALITY = 100

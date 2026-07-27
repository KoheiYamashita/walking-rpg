package com.walkingrpg.shared.platform.map

import android.content.Context
import android.util.Log
import com.walkingrpg.shared.BuildConfig
import com.walkingrpg.shared.domain.map.GeoPoint
import com.walkingrpg.shared.domain.map.MapCamera
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Android版 [LocalMapSource]。
 *
 * MapLibre Native の `pmtiles://` はレンジ読み出しを要求するため、
 * APKのassetsを直接は開けない（AssetManagerFileSourceがレンジ非対応）。
 * そこで初回起動時に assets から内部ストレージへコピーし、
 * `pmtiles://file://<絶対パス>` として参照する。
 */
internal class AndroidLocalMapSource(
    private val context: Context,
) : LocalMapSource {

    override suspend fun installTiles(): String? = withContext(Dispatchers.IO) {
        val assetName = findBundledTiles() ?: return@withContext null
        val destination = File(context.filesDir, "$TILES_DIR/$assetName")

        runCatching { copyIfNeeded(assetName, destination) }
            .onFailure { Log.e(TAG, "PMTilesの展開に失敗した: $assetName", it) }
            .getOrNull()
            ?.absolutePath
    }

    override fun defaultCamera(): MapCamera = MapCamera(
        center = GeoPoint(
            latitude = BuildConfig.MAP_CENTER_LAT.toDouble(),
            longitude = BuildConfig.MAP_CENTER_LON.toDouble(),
        ),
        zoom = BuildConfig.MAP_ZOOM.toDouble(),
    )

    /** assets/map 配下の最初の `.pmtiles` を採用する（ファイル名をコードに固定しない）。 */
    private fun findBundledTiles(): String? =
        runCatching { context.assets.list(TILES_DIR)?.firstOrNull { it.endsWith(TILES_EXTENSION) } }
            .onFailure { Log.e(TAG, "assets/$TILES_DIR の列挙に失敗した", it) }
            .getOrNull()

    /**
     * サイズが一致していれば再コピーしない。
     * 書き込みは一時ファイル経由にして、途中で落ちても壊れたファイルが残らないようにする。
     */
    @Throws(IOException::class)
    private fun copyIfNeeded(assetName: String, destination: File): File {
        val assetPath = "$TILES_DIR/$assetName"
        val assetSize = context.assets.openFd(assetPath).use { it.length }
        if (destination.isFile && destination.length() == assetSize) return destination

        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile, "${destination.name}.tmp")
        context.assets.open(assetPath).use { input ->
            temporary.outputStream().use { output -> input.copyTo(output) }
        }
        if (!temporary.renameTo(destination)) {
            temporary.delete()
            throw IOException("PMTilesの配置に失敗した: ${destination.absolutePath}")
        }
        return destination
    }

    private companion object {
        const val TAG = "LocalMapSource"
        const val TILES_DIR = "map"
        const val TILES_EXTENSION = ".pmtiles"
    }
}

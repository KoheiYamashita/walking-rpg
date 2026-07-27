package com.walkingrpg.shared.platform.map

import com.walkingrpg.shared.domain.map.GeoPoint
import com.walkingrpg.shared.domain.map.MapCamera

/**
 * iOS版 [LocalMapSource]（プレースホルダ）。
 *
 * MapLibre iOS も `pmtiles://` に対応しているため方式はAndroidと同じにできるが、
 * 検証環境（Linux）でビルドできないため実装は後続issueに送る。
 * ここでは常に「タイル未同梱」を返し、地図画面は未実装表示になる。
 */
internal class IosLocalMapSource : LocalMapSource {

    // TODO(issue #4 申し送り): バンドル同梱のPMTilesをDocumentsへ展開して絶対パスを返す
    override suspend fun installTiles(): String? = null

    override fun defaultCamera(): MapCamera = MapCamera(GeoPoint(0.0, 0.0), zoom = 1.0)
}

package com.walkingrpg.shared.platform.map

import com.walkingrpg.shared.BuildConfig
import com.walkingrpg.shared.domain.map.GeoPoint
import com.walkingrpg.shared.domain.map.MapCamera

/**
 * Android版 [MapCameraSource]。
 *
 * 初期表示位置は対象圏そのもの＝位置情報なのでリポジトリに書かない。
 * Git管理外の `local.properties`（`map.center.lat` / `map.center.lon` / `map.zoom`）を
 * ビルド時にBuildConfigへ通してある。未設定なら世界全体（0,0 / z1）。
 */
internal class AndroidMapCameraSource : MapCameraSource {

    override fun defaultCamera(): MapCamera = MapCamera(
        center = GeoPoint(
            latitude = BuildConfig.MAP_CENTER_LAT.toDouble(),
            longitude = BuildConfig.MAP_CENTER_LON.toDouble(),
        ),
        zoom = BuildConfig.MAP_ZOOM.toDouble(),
    )
}

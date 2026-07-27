package com.walkingrpg.shared.platform.map

import com.walkingrpg.shared.domain.map.GeoPoint
import com.walkingrpg.shared.domain.map.MapCamera

/**
 * iOS版 [MapCameraSource]（プレースホルダ）。
 *
 * iOSの地図埋め込み自体が後続issue送りなので、当面は世界全体を返す。
 * TODO(issue #4 申し送り): xcconfig等のGit管理外設定から初期位置を読む
 */
internal class IosMapCameraSource : MapCameraSource {

    override fun defaultCamera(): MapCamera = MapCamera(GeoPoint(0.0, 0.0), zoom = 1.0)
}

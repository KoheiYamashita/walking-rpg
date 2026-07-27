package com.walkingrpg.shared.domain.map

import com.walkingrpg.shared.domain.walk.CurrentLocationRepository

/**
 * 地図画面に出すもの一式を組み立てるUseCase。
 *
 * スパイク（issue #4）では歩行ログがまだ無いので、抽象レイヤーの検証用に
 * カメラ中心から機械的に導出したダミーwayを返す。純関数なので同じ入力からは
 * 必ず同じ出力になる（architecture.md §0「状態 = 歩行ログの累積」の性質を先取り）。
 *
 * 後続issue（#6 map matching）で、ここが `passage` からの導出に置き換わる。
 *
 * カメラの中心は**現在地が取れればそこ、取れなければ広域デフォルト**
 * （[MapCamera.WIDE_DEFAULT]）。ユーザー固有の座標をリポジトリに置かない方針なので、
 * 「対象圏を設定ファイルで指定する」仕組みは持たない。現在地が取れるかどうかは
 * [CurrentLocationRepository] の向こう側（権限・測位）に閉じており、
 * このUseCaseは `null` かどうかしか見ない（権限を促す表示はUI層の担当）。
 */
class GetMapSceneUseCase(
    private val currentLocationRepository: CurrentLocationRepository,
) {
    suspend operator fun invoke(): MapScene {
        val userLocation = currentLocationRepository.currentFix()
            ?.let { GeoPoint(latitude = it.latitude, longitude = it.longitude) }
        val camera = if (userLocation == null) {
            MapCamera.WIDE_DEFAULT
        } else {
            MapCamera(center = userLocation, zoom = FOCUSED_ZOOM)
        }

        return MapScene(
            camera = camera,
            highlights = demoHighlights(camera.center),
            userLocation = userLocation,
        )
    }

    /**
     * 中心から東西に伸びる3本のダミーway。座標は中心からの相対オフセットのみで、
     * 実在の場所をコードに書かないための措置。
     */
    private fun demoHighlights(center: GeoPoint): List<WayHighlight> =
        List(DEMO_WAY_COUNT) { index ->
            val latOffset = (index - DEMO_WAY_COUNT / 2) * DEMO_WAY_SPACING_DEG
            WayHighlight(
                wayId = "demo-way-$index",
                shape = List(DEMO_WAY_VERTEX_COUNT) { vertex ->
                    val progress = vertex.toDouble() / (DEMO_WAY_VERTEX_COUNT - 1) - 0.5
                    GeoPoint(
                        latitude = center.latitude + latOffset,
                        longitude = center.longitude + progress * DEMO_WAY_LENGTH_DEG,
                    )
                },
                depth = index + 1,
            )
        }

    private companion object {
        /** 現在地が取れたときのズーム（散歩の縮尺＝街区が見える程度）。 */
        const val FOCUSED_ZOOM = 15.0

        const val DEMO_WAY_COUNT = 3
        const val DEMO_WAY_VERTEX_COUNT = 5
        const val DEMO_WAY_SPACING_DEG = 0.0015
        const val DEMO_WAY_LENGTH_DEG = 0.006
    }
}

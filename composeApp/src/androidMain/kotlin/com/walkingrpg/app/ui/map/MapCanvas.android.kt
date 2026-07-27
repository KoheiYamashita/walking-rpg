package com.walkingrpg.app.ui.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.gson.JsonObject
import com.walkingrpg.shared.domain.map.MapCamera
import com.walkingrpg.shared.domain.map.WayHighlight
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/**
 * 背景タイルのスタイル（architecture.md §1）。
 *
 * OpenFreeMapのホスト済みスタイル。APIキー・利用登録なしで使える。
 * `positron` は彩度の低いグレー基調で、上に重ねる抽象レイヤー（way色）が埋もれない
 * （design.md §8「実地図＋抽象レイヤー」「地図描画にアート量を投入しない」）。
 */
private const val STYLE_URL = "https://tiles.openfreemap.org/styles/positron"

private const val HIGHLIGHT_SOURCE_ID = "walked-ways"
private const val HIGHLIGHT_LAYER_ID = "walked-ways-line"
private const val COLOR_PROPERTY = "color"

/**
 * Android版の地図ビュー。MapLibre Native を [AndroidView] で埋め込む。
 *
 * タイルはオンライン取得。MapLibreが取得済みタイルをローカルキャッシュ（SQLite）に
 * 持つため、圏外では一度表示した範囲がそのまま出る。未取得の範囲は空白になるだけで、
 * 取得失敗が例外になることはない。
 */
@Composable
actual fun MapCanvas(
    camera: MapCamera,
    highlights: List<WayHighlight>,
    modifier: Modifier,
) {
    val context = LocalContext.current

    // MapLibre.getInstance は MapView 生成より先に一度だけ呼ぶ必要がある。
    val mapView = remember(context) {
        MapLibre.getInstance(context)
        MapView(context)
    }

    val features = remember(highlights) { highlights.toFeatureCollection() }

    MapViewLifecycle(mapView)

    AndroidView(
        modifier = modifier,
        factory = {
            mapView.apply {
                onCreate(null)
                getMapAsync { map ->
                    map.cameraPosition = CameraPosition.Builder()
                        .target(LatLng(camera.center.latitude, camera.center.longitude))
                        .zoom(camera.zoom)
                        .build()
                    map.setStyle(Style.Builder().fromUri(STYLE_URL)) { style ->
                        // 抽象レイヤーはOpenFreeMapのスタイルの一番上に重ねる。
                        style.addSource(GeoJsonSource(HIGHLIGHT_SOURCE_ID, features))
                        style.addLayer(highlightLayer())
                    }
                }
            }
        },
        update = { view ->
            // 歩行ログが増えれば描き直す（スパイクでは初回のみ実質動く）。
            view.getMapAsync { map ->
                map.style?.getSourceAs<GeoJsonSource>(HIGHLIGHT_SOURCE_ID)?.setGeoJson(features)
            }
        },
    )
}

/**
 * way単位の色づけレイヤー。
 * 色はfeatureの `color` プロパティから引く（data-driven styling）ので、
 * 1レイヤーのままway1本ごとに違う色を塗れる。
 */
private fun highlightLayer(): LineLayer =
    LineLayer(HIGHLIGHT_LAYER_ID, HIGHLIGHT_SOURCE_ID).withProperties(
        PropertyFactory.lineColor(Expression.get(COLOR_PROPERTY)),
        PropertyFactory.lineWidth(6f),
        PropertyFactory.lineOpacity(0.85f),
        PropertyFactory.lineCap("round"),
        PropertyFactory.lineJoin("round"),
    )

private fun List<WayHighlight>.toFeatureCollection(): FeatureCollection =
    FeatureCollection.fromFeatures(
        map { highlight ->
            val line = LineString.fromLngLats(
                highlight.shape.map { Point.fromLngLat(it.longitude, it.latitude) },
            )
            val properties = JsonObject().apply {
                addProperty(COLOR_PROPERTY, depthColorHex(highlight.depth))
            }
            Feature.fromGeometry(line, properties, highlight.wayId)
        },
    )

/** MapViewはAndroidのライフサイクルコールバックを自前で受け取る必要がある。 */
@Composable
private fun MapViewLifecycle(mapView: MapView) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onDestroy()
        }
    }
}

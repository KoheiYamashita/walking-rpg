package com.walkingrpg.app.ui.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.walkingrpg.shared.domain.map.GeoPoint
import com.walkingrpg.shared.domain.map.MapCamera
import com.walkingrpg.shared.domain.map.WayHighlight

/**
 * iOS版の地図ビュー（プレースホルダ）。
 *
 * 方式はAndroidと同一にできる（OpenFreeMapのスタイルURLを `MLNMapView` に渡すだけ）。
 * ただし導入は `iosApp.xcodeproj` へのSPM追加が必要で、Linux環境ではビルド検証が
 * できないため後続issueに送る。
 *
 * TODO(issue #4 申し送り): SPM `https://github.com/maplibre/maplibre-gl-native-distribution`
 *  （product: `MapLibre`）を追加し、`UIKitView` で `MLNMapView` を埋め込む。
 */
@Composable
actual fun MapCanvas(
    camera: MapCamera,
    highlights: List<WayHighlight>,
    userLocation: GeoPoint?,
    followUserLocation: Boolean,
    modifier: Modifier,
) {
    Column(
        modifier = modifier.padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = "地図は未実装", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "iOSのMapLibre埋め込みは後続issueで対応する（Android優先）。",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

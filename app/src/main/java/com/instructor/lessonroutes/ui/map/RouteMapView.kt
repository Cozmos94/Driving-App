package com.instructor.lessonroutes.ui.map

import android.os.Bundle
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import org.maplibre.android.maps.MapView

/** Free, keyless vector tile style. Liberty is OpenFreeMap's general-purpose style. */
const val OPENFREEMAP_LIBERTY_STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"

/**
 * Step 1 of the build order: prove a map renders on screen from a free tile source,
 * with no API key and no billing account. Route/point rendering comes in step 3.
 *
 * MapLibre's `MapView` is a plain Android View with its own lifecycle (onStart/onResume/
 * onPause/onStop/onDestroy/onLowMemory) that must be driven manually — it does not know
 * about Compose or the host Activity's lifecycle on its own. We bridge the two with
 * DisposableEffect + LifecycleEventObserver below.
 */
@Composable
fun RouteMapView(
    modifier: Modifier = Modifier,
    styleUrl: String = OPENFREEMAP_LIBERTY_STYLE_URL,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val mapViewRef = remember { mutableStateOf<MapView?>(null) }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { context ->
            MapView(context).also { view ->
                mapViewRef.value = view
                view.onCreate(Bundle())
                view.getMapAsync { map ->
                    map.setStyle(styleUrl)
                }
            }
        },
    )

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            val view = mapViewRef.value ?: return@LifecycleEventObserver
            when (event) {
                Lifecycle.Event.ON_START -> view.onStart()
                Lifecycle.Event.ON_RESUME -> view.onResume()
                Lifecycle.Event.ON_PAUSE -> view.onPause()
                Lifecycle.Event.ON_STOP -> view.onStop()
                Lifecycle.Event.ON_DESTROY -> view.onDestroy()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            // Don't call view.onDestroy() here too: the ON_DESTROY branch above already
            // does, and it fires first as part of the same teardown — calling it twice
            // is not safe to assume idempotent.
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
}

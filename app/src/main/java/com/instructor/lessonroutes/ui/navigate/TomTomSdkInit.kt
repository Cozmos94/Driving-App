package com.instructor.lessonroutes.ui.navigate

import android.content.Context
import android.util.Log
import com.instructor.lessonroutes.BuildConfig
import com.tomtom.sdk.common.configuration.buildSdkConfiguration
import com.tomtom.sdk.init.TomTomSdk

private const val LOG_TAG = "TomTomSdkInit"

/**
 * Ensures [TomTomSdk] is initialized exactly once for this process. TomTomSdk
 * is a real process-wide singleton -- calling [TomTomSdk.initialize] a second
 * time throws "TomTomSdk is already initialized" instead of being a no-op,
 * which is exactly what broke the standalone TomTom nav spike screen the
 * first few times it was re-entered (see TomTomNavSpikeScreen.kt's own
 * comment on that). This shared helper is what TomTomNavigationScreen.kt
 * (the real "Navigate" integration) uses instead of duplicating that
 * guard -- it's an `object`, not tied to any one screen's composition
 * lifecycle, so it stays correct regardless of how many screens end up
 * calling it or how many times any one of them re-enters composition.
 */
object TomTomSdkInit {
    @Volatile
    private var initialized = false

    fun ensureInitialized(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            try {
                TomTomSdk.initialize(
                    context.applicationContext,
                    buildSdkConfiguration(
                        context = context.applicationContext,
                        apiKey = BuildConfig.TOMTOM_API_KEY,
                    ),
                )
                initialized = true
            } catch (e: Exception) {
                if (e.message?.contains("already initialized", ignoreCase = true) == true) {
                    // Genuinely already ready (e.g. the spike screen
                    // initialized it first this session) -- treat it as
                    // such rather than a real failure.
                    Log.w(LOG_TAG, "SDK already initialized elsewhere -- treating as ready")
                    initialized = true
                } else {
                    throw e
                }
            }
        }
    }
}

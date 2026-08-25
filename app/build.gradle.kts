import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Read from local.properties (git-ignored, never committed) rather than hardcoding.
// Falls back to an empty string so the project still builds for anyone who hasn't
// set a key yet -- Phase 2 overlay code must treat an empty key as "feature off".
val tfnswApiKey: String = run {
    val properties = Properties()
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use { properties.load(it) }
    }
    properties.getProperty("TFNSW_API_KEY", "")
}

// Same pattern as tfnswApiKey above. Geoapify (maps/tiles + geocoding + routing,
// replacing OpenFreeMap/Nominatim/OSRM) has a free tier with no card required
// (100k+/day-ish depending on the API, plenty for this app's actual usage) but
// still needs a real account/key -- see README's Geoapify section.
val geoapifyApiKey: String = run {
    val properties = Properties()
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use { properties.load(it) }
    }
    properties.getProperty("GEOAPIFY_API_KEY", "")
}

// Same pattern again. This is the TomTom Navigation SDK's *runtime* API key --
// separate from the Maven repository Identity Token needed just to download the
// SDK itself (that one goes in settings.gradle.kts instead, Gradle credentials
// aren't read via BuildConfig). See the TomTom "Project Setup" section of
// README once it's written up.
val tomtomApiKey: String = run {
    val properties = Properties()
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use { properties.load(it) }
    }
    properties.getProperty("TOMTOM_API_KEY", "")
}

android {
    namespace = "com.instructor.lessonroutes"
    // Bumped 34->35: a hard requirement of the TomTom Navigation SDK (confirmed
    // live against its current project-setup docs) -- targetSdk deliberately
    // left at 34 for now, since that's a bigger behavioral surface than just
    // the compiler/tooling version compileSdk controls.
    compileSdk = 35

    defaultConfig {
        applicationId = "com.instructor.lessonroutes"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
        buildConfigField("String", "TFNSW_API_KEY", "\"$tfnswApiKey\"")
        buildConfigField("String", "GEOAPIFY_API_KEY", "\"$geoapifyApiKey\"")
        buildConfigField("String", "TOMTOM_API_KEY", "\"$tomtomApiKey\"")

        // TomTom SDK requirements, confirmed live from its own docs:
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
        // TomTom ships the SDK in flavors (e.g. a smaller "lite" vs "complete"
        // feature set) via a Gradle product flavor dimension it declares
        // internally -- "complete" is the one their own quickstart uses and
        // needs no separate Maven repo credentials, unlike some other flavor.
        missingDimensionStrategy("tomtom-sdk-version", "complete")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true // needed to generate BuildConfig.TFNSW_API_KEY (AGP 8+ opt-in)
    }

    packaging {
        // MapLibre ships native .so libraries per-ABI; avoid duplicate-file packaging clashes.
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// Left in as a cheap safety net even though the actual root cause (mixing
// navigation-android's independent 1.26.8 version family with everything
// else's 2.4.2 -- see tomtom-sdk-navigation's comment in libs.versions.toml)
// is now fixed by depending on the plain, properly-2.4.2-versioned
// "navigation" artifact instead. Excluding a module that's no longer on the
// classpath at all is a harmless no-op, so there's no reason to pull this
// back out on the chance some other transitive path still reaches it.
configurations.all {
    exclude(group = "com.tomtom.sdk.telemetry", module = "sensoris")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.core)
    debugImplementation(libs.androidx.ui.tooling)

    // Map (step 1: just rendering; no key, no billing account)
    implementation(libs.maplibre.android.sdk)

    // Room (step 2 — declared now so the version catalog entry is in place)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Location (step 6 — declared now, unused until FusedLocationProvider work starts)
    implementation(libs.play.services.location)

    implementation(libs.kotlinx.coroutines.android)

    // Navigation between screens (step 4+)
    implementation(libs.androidx.navigation.compose)

    // Phase 2: Transport for NSW live hazards feed
    implementation(libs.okhttp)

    // TomTom Navigation SDK spike -- see ui/navspike/TomTomNavSpikeScreen.kt.
    // provider-simulation is a stand-in GPS source for this spike only; a real
    // build would use a real location provider instead.
    implementation(libs.tomtom.sdk.init)
    implementation(libs.tomtom.sdk.common.configuration)
    implementation(libs.tomtom.sdk.location.provider.simulation)
    implementation(libs.tomtom.sdk.routing.route.planner)
    // Was navigation-android (a real artifact, but independently versioned at
    // 1.26.8 -- see tomtom-sdk-navigation's own comment in libs.versions.toml
    // for the two wrong guesses that preceded this and the cascade of
    // duplicate-class errors that came from mixing that version family with
    // everything else's 2.4.2). Plain "navigation" publishes proper 2.4.2
    // releases and depends only on other 2.4.2 artifacts.
    implementation(libs.tomtom.sdk.navigation)
    // Real turn-by-turn map + guidance for the real "Navigate" button
    // (TomTomNavigationScreen.kt) -- see these libraries' own comments in
    // libs.versions.toml for why (a Fragment-based UI module was tried first
    // and confirmed to render no map at all).
    implementation(libs.tomtom.sdk.location.provider.default)
    implementation(libs.tomtom.sdk.maps.map.display.compose.standard)
    implementation(libs.tomtom.sdk.maps.visualization.compose)
}

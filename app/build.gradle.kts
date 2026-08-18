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

android {
    namespace = "com.instructor.lessonroutes"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.instructor.lessonroutes"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
        buildConfigField("String", "TFNSW_API_KEY", "\"$tfnswApiKey\"")
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

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
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
}

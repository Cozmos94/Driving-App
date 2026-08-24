import java.util.Properties

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}

// Same local.properties (git-ignored) convention as every other key in this
// project -- see app/build.gradle.kts. Read here too (settings.gradle.kts runs
// in a separate script context, no rootProject.file() available yet) purely
// for the TomTom Maven repo's *download* credentials, which are a different
// thing from BuildConfig.TOMTOM_API_KEY (that one's the runtime key, read in
// app/build.gradle.kts as usual). TomTom's own docs show this repo working
// with NO credentials at all for the "complete" SDK flavor this app uses
// (see missingDimensionStrategy in app/build.gradle.kts) -- credentials only
// get applied below if actually present, so this doesn't block a sync while
// Corey's Identity Token situation is still unclear.
val tomtomRepoProperties = Properties().apply {
    val file = File(rootDir, "local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val tomtomRepoUsername: String = tomtomRepoProperties.getProperty("TOMTOM_REPO_USERNAME", "")
val tomtomRepoIdentityToken: String = tomtomRepoProperties.getProperty("TOMTOM_REPO_IDENTITY_TOKEN", "")

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // MapLibre Native Android SDK is published on Maven Central under org.maplibre.gl.
        // No extra repo/key/account needed.
        maven {
            url = uri("https://repositories.tomtom.com/artifactory/maven")
            if (tomtomRepoUsername.isNotEmpty()) {
                credentials {
                    username = tomtomRepoUsername
                    password = tomtomRepoIdentityToken
                }
            }
        }
    }
}

rootProject.name = "LessonRoutes"
include(":app")

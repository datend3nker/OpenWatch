plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// The watch app shares the phone app's applicationId so Play lists it as a
// form factor of OpenWatch, not as a second app. Same rules follow: every
// artifact in one listing needs a unique versionCode, so the watch owns the
// range 2_000_000_000 to 2_099_999_999. The phone counter stays below that.
// Play caps versionCode at 2_100_000_000.
val wearVersionCodeBase = 2_000_000_000
// The release value lives in gradle.properties, shared with app/.
val wearBaseVersionCode = providers.gradleProperty("openwatch.versionCode")
    .map { wearVersionCodeBase + it.toInt() }
val wearBaseVersionName = providers.gradleProperty("openwatch.versionName")

// Same signing as the phone app, from the same environment variables: both
// form factors ship under one applicationId, so one key signs both.
val releaseStoreFilePath = System.getenv("OPENWATCH_RELEASE_STORE_FILE")
val releaseStorePassword = System.getenv("OPENWATCH_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = System.getenv("OPENWATCH_RELEASE_KEY_ALIAS")
val releaseKeyPassword = System.getenv("OPENWATCH_RELEASE_KEY_PASSWORD")
val isPkcs12ReleaseStore = releaseStoreFilePath
    ?.lowercase()
    ?.let { it.endsWith(".p12") || it.endsWith(".pfx") || it.endsWith(".pkcs12") }
    ?: false
val effectiveReleaseKeyPassword = if (isPkcs12ReleaseStore) {
    releaseStorePassword
} else {
    releaseKeyPassword
}
val hasReleaseSigning = listOf(
    releaseStoreFilePath,
    releaseStorePassword,
    releaseKeyAlias,
    effectiveReleaseKeyPassword,
).all { !it.isNullOrBlank() }
val signDebugWithReleaseKey = System.getenv("OPENWATCH_SIGN_DEBUG_WITH_RELEASE_KEY") == "true"
// Same switch as the phone app: a published debug APK is not debuggable, and
// is not run through R8 (it was until 2026-10; see app/build.gradle.kts).
val publishedDebugApk = System.getenv("OPENWATCH_PUBLISHED_DEBUG_APK") == "true"
val wearVersionCodeOverride = providers.environmentVariable("OPENWATCH_WEAR_VERSION_CODE")
    .map { it.toInt() }
val wearVersionNameOverride = providers.environmentVariable("OPENWATCH_WEAR_VERSION_NAME")
// Same rule as the phone app: a nightly carries "-nightly" unless the
// release pipeline supplied an explicit version name.
val wearNightlyVersionNameSuffix = wearVersionNameOverride
    .map { "" }
    .orElse("-nightly")
val wearVersionCode = wearVersionCodeOverride.orElse(wearBaseVersionCode).get()
require(wearVersionCode in (wearVersionCodeBase + 1)..2_099_999_999) {
    "OPENWATCH_WEAR_VERSION_CODE $wearVersionCode is outside the watch range " +
        "${wearVersionCodeBase + 1}..2099999999. Phone codes live below it."
}

android {
    // Kotlin namespace stays under .wear; only the applicationId is shared.
    namespace = "tech.mmarca.openwatch.wear"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "tech.mmarca.openwatch"
        minSdk = 30
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        targetSdk = 36
        versionCode = wearVersionCode
        versionName = wearVersionNameOverride.orElse(wearBaseVersionName).get()
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(checkNotNull(releaseStoreFilePath))
                storePassword = checkNotNull(releaseStorePassword)
                keyAlias = checkNotNull(releaseKeyAlias)
                keyPassword = checkNotNull(effectiveReleaseKeyPassword)
            }
        }
    }

    buildTypes {
        // Same suffix as the phone debug build, so a debug watch pairs with a
        // debug phone.
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            if (signDebugWithReleaseKey && hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            if (publishedDebugApk) {
                isDebuggable = false
            }
        }

        // R8 on, the same way the phone app does it. The newer
        // optimization.enable DSL needs an experimental AGP flag.
        release {
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }

        create("nightly") {
            initWith(getByName("release"))
            versionNameSuffix = wearNightlyVersionNameSuffix.get()
            matchingFallbacks += listOf("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    // The release build runs after the gate's lint (verifyWearCi); lintVital
    // would repeat part of it inside the signing build.
    lint {
        checkReleaseBuilds = false
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    implementation(project(":wearlink"))
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    // Same icon set as the phone app, so a metric keeps its glyph on the watch.
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.wear.compose.material3)
    implementation(libs.androidx.wear.compose.navigation)
    // The watch face pill of a running workout.
    implementation(libs.androidx.wear.ongoing)
    // Ambient mode for a workout and a running measurement (AmbientLifecycleObserver).
    implementation(libs.androidx.wear)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.core.ktx)
    // Same drag-to-reorder library as the phone dashboard.
    implementation(libs.reorderable)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    // The metric tiles: Material 3 tile layouts, no Play Services.
    implementation(libs.androidx.wear.tiles)
    implementation(libs.androidx.wear.protolayout)
    implementation(libs.androidx.wear.protolayout.material3)
    implementation(libs.androidx.wear.protolayout.material)
    // A suspend function as the ListenableFuture a TileService returns.
    implementation(libs.androidx.concurrent.futures.ktx)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    // Real org.json for local tests (the android.jar copy is a throwing stub): the feature vector is JSON.
    testImplementation(libs.org.json)
    // The metric store against real SQLite, on a watch or emulator; CI compiles it.
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}

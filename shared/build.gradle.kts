import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    // App-wide experimental opt-ins. Top-level compilerOptions propagates to
    // ALL targets; the Native/iOS compile is stricter than Android's (FlowRow /
    // FlowColumn layout + foundation APIs used across the shared Compose UI).
    compilerOptions {
        optIn.add("androidx.compose.foundation.ExperimentalFoundationApi")
        optIn.add("androidx.compose.foundation.layout.ExperimentalLayoutApi")
    }

    // jvmTarget lives on each JVM-flavoured target — multiplatform's top-level
    // compilerOptions doesn't expose it, so we set it on androidTarget only.
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    listOf(iosX64(), iosArm64(), iosSimulatorArm64()).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "shared"
            isStatic = true
            // Surface MainViewController() to Swift so iosApp/ContentView.swift can host it.
            export(libs.voyager.navigator)
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            // Extended Material icon set — gives us lucide-like glyphs
            // (Movie/Film, Tv, NotificationsActive, etc.) that the core
            // material icon set doesn't ship.
            implementation(compose.materialIconsExtended)
            implementation(compose.components.resources)
            implementation(compose.components.uiToolingPreview)
            implementation(compose.ui)

            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)

            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.client.logging)
            implementation(libs.ktor.client.auth)
            implementation(libs.ktor.serialization.kotlinx.json)

            api(libs.voyager.navigator)
            implementation(libs.voyager.screenmodel)
            implementation(libs.voyager.transitions)

            implementation(libs.coil.compose)
            implementation(libs.coil.network.ktor)

            // Lucide icons — vendored as ImageVectors in
            // commonMain/com/composables/icons/lucide/Lucide.kt (same package +
            // accessor API). The external icons-lucide-cmp artifact only ships a
            // Kotlin 2.2.x iOS klib (ABI 2.2.0), unreadable by this project's
            // Kotlin 2.1.0 Native compiler; vendoring keeps iOS buildable
            // without a toolchain bump. Re-generate via tools/gen_lucide.py.
        }

        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
            implementation(libs.androidx.datastore.preferences)
            // androidx.activity.compose.BackHandler — the player owns the
            // system-back press explicitly (bug #151: implicit Voyager
            // beta03 back-redispatch looped Detail<->Player).
            implementation(libs.androidx.activity.compose)
            // androidx.core.view.WindowInsetsControllerCompat is used in
            // the PlayerScreen's fullscreen toggle to hide/show system
            // bars — it ships in androidx.core-ktx.
            implementation(libs.androidx.core.ktx)
            // Media3 lives here (not in androidApp) because the
            // `expect class PlayerScreen` actual is defined in
            // shared/src/androidMain. iOS will get its own actual using
            // AVPlayer; no shared media deps for now.
            implementation(libs.media3.exoplayer)
            implementation(libs.media3.exoplayer.hls)
            implementation(libs.media3.ui)
            implementation(libs.media3.datasource.okhttp)
            // On-disk media cache (SimpleCache/CacheDataSource/CacheWriter) for
            // the Zap client-side prefetch + StandaloneDatabaseProvider backing
            // it. Transitively present via exoplayer; declared explicitly.
            implementation(libs.media3.datasource)
            implementation(libs.media3.database)
            // media3-extractor exposes DefaultSubtitleParserFactory which
            // PlayerScreen passes to SingleSampleMediaSource.Factory so
            // VTT side-cars get parsed during extraction (Media3 1.5+
            // requirement; without it the player crashes mid-prepare
            // with "Legacy decoding is disabled" for text/vtt samples).
            implementation(libs.media3.extractor)
        }

        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
    }
}

android {
    namespace = "cloud.nalet.chino.mobile.shared"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

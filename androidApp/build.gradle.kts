import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

// --- CI build inputs (all optional; defaults preserve local/dev behaviour) ---
// versionCode: CI sets VERSION_CODE=$CI_PIPELINE_IID so every Play upload climbs.
val ciVersionCode = System.getenv("VERSION_CODE")?.toIntOrNull()
// Release signing: CI decodes a base64 upload keystore to SIGNING_KEYSTORE_FILE.
val releaseKeystore = System.getenv("SIGNING_KEYSTORE_FILE")?.takeIf { it.isNotBlank() && file(it).exists() }
// OAuth redirect scheme (the `chino` Keycloak client's registered redirect URI).
// This is a SEPARATE identifier from the published app id — it stays
// cloud.nalet.chino (what the Keycloak client knows) even though the app id is
// io.github.zaentrum.chino. Debug builds append ".debug" (also registered).
val redirectBase = "cloud.nalet.chino"

// Neutral self-host server defaults. The client resolves its live server (API
// base + OIDC issuer/client) from the persisted ServerConfig set via the in-app
// Add-Server flow, so these are only non-UI fallbacks before a server is
// connected. The committed default is EMPTY — an operator building their own
// distribution can inject real values without code changes via gradle project
// properties (-PapiBaseUrl=..., -PoidcIssuer=..., or gradle.properties /
// ~/.gradle/gradle.properties / ORG_GRADLE_PROJECT_* env vars).
fun prop(name: String): String = (project.findProperty(name) as? String) ?: ""
val apiBaseUrl = prop("apiBaseUrl")
val oidcIssuer = prop("oidcIssuer")

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    sourceSets {
        androidMain.dependencies {
            implementation(projects.shared)
            implementation(libs.androidx.core.ktx)
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.lifecycle.runtime.ktx)
            implementation(libs.androidx.browser)
            implementation(libs.appauth)
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
        }
        // Compose UI Test stack — instrumented (runs on the device under
        // `am instrument`). androidInstrumentedTest is the Kotlin-MPP name
        // for what the standalone AGP plugin calls `androidTest`.
        getByName("androidInstrumentedTest").dependencies {
            implementation(projects.shared)
            implementation(libs.junit)
            implementation(libs.androidx.test.runner)
            implementation(libs.androidx.test.rules)
            implementation(libs.androidx.test.ext.junit)
            implementation(libs.compose.ui.test.junit4)
            implementation(compose.ui)
            implementation(compose.material3)
            // Needed at runtime so the test APK can launch composables in
            // an empty ComponentActivity host.
            implementation(libs.compose.ui.test.manifest)
        }
    }
}

// Published applicationId base. Forks override with -PchinoAppId=... (or in
// gradle.properties); defaults to the project's OSS reverse-DNS id from its
// GitHub org Pages (io.github.zaentrum ← zaentrum.github.io) — portable on
// hand-off, not tied to any operator's domain. The Kotlin `namespace` below is
// the internal code namespace and is intentionally separate (still
// cloud.nalet.chino.*); the OAuth redirect scheme is separate too.
val chinoAppId = (project.findProperty("chinoAppId") as? String) ?: "io.github.zaentrum.chino"

android {
    namespace = "cloud.nalet.chino.mobile.android"
    compileSdk = 35

    defaultConfig {
        // Single unified app id — no product flavors. Forks override via
        // -PchinoAppId; debug builds append ".debug" (see buildTypes below).
        applicationId = chinoAppId
        minSdk = 24
        targetSdk = 35
        versionCode = ciVersionCode ?: 1
        versionName = "0.1.0"
        // AndroidJUnitRunner — invoked by `am instrument` to enumerate +
        // execute @Test methods inside the androidInstrumentedTest APK.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        resValue("string", "app_name", "Chino")

        // Neutral bring-your-own-server client: everything below is an internal,
        // non-UI fallback only (empty by default). The connected server's
        // /api/config + OIDC discovery supplies the real values at runtime.
        buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrl\"")
        // Add-Server prefill: blank (neutral) — empty field, generic placeholder,
        // no baked operator URL.
        buildConfigField("String", "SERVER_PRESET", "\"\"")
        buildConfigField("String", "OIDC_ISSUER", "\"$oidcIssuer\"")
        buildConfigField("String", "OIDC_CLIENT_ID", "\"chino\"")
        buildConfigField("String", "DISPLAY_NAME", "\"Chino\"")
        // No flavors anymore; kept as a stable tag for telemetry / bug reports.
        buildConfigField("String", "FLAVOR_NAME", "\"prod\"")
        // OAuth redirect scheme (separate from app id). Debug builds use
        // base+".debug"; release is overridden to the bare base in
        // androidComponents below. Both are registered on the Keycloak client.
        buildConfigField("String", "OIDC_REDIRECT_BASE", "\"$redirectBase\"")
        manifestPlaceholders["appAuthRedirectScheme"] = "$redirectBase.debug"
    }

    signingConfigs {
        // Optional STABLE debug keystore. When a `debug.keystore` is present in
        // this module, every build (local + CI) shares ONE debug signature, so
        // `adb install -r` updates the app in place without wiping app data and
        // devices keep their login across sideloaded updates. Debug keys are
        // public by design (the standard password is "android"), so no real
        // keystore is committed — drop your own `debug.keystore` here (or let
        // AGP auto-generate the default `~/.android/debug.keystore`). The
        // release/upload key still comes from CI env vars below.
        val debugKeystore = file("debug.keystore")
        if (debugKeystore.exists()) {
            getByName("debug") {
                storeFile = debugKeystore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
        // Supplied by CI via env (base64 upload keystore -> SIGNING_KEYSTORE_FILE).
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("SIGNING_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (releaseKeystore != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    lint {
        // lintVitalRelease crashes under AGP 8.7 lint + Kotlin 2.1 analysis API.
        // Lint is code-quality gating, not part of producing the artifact, so
        // skip it on release builds to keep the AAB/APK build green.
        checkReleaseBuilds = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // `kotlinOptions { jvmTarget = ... }` is only valid with the kotlin-android
    // plugin; we set jvmTarget on androidTarget above instead.
    buildFeatures {
        buildConfig = true
    }
    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/INDEX.LIST",
        )
    }
}

androidComponents {
    onVariants { variant ->
        // Release builds have no ".debug" suffix, so the RedirectUriReceiver
        // scheme is the bare redirect base; debug keeps base+".debug" (the
        // defaultConfig placeholder). Both are registered on the Keycloak client.
        if (variant.buildType == "release") {
            variant.manifestPlaceholders.put("appAuthRedirectScheme", redirectBase)
        }
    }
}

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
// Point the prod flavor at the beta backend until the prod backend is live.
val prodPointsToBeta = (project.findProperty("prodPointsToBeta") as? String) == "true"
// OIDC redirect scheme base. When prod points to beta it uses the beta OIDC
// client, whose registered redirect URIs are the .beta scheme(s) — so the prod
// build must send/claim the beta redirect scheme too, not its own app id.
// This is the OAuth redirect SCHEME (the `chino` Keycloak client's registered
// redirect URIs), kept as cloud.nalet.chino even though the published app id is
// now io.github.zaentrum.chino — a redirect scheme is a separate identifier and
// need not match the app id. While prod points at beta it uses the beta scheme
// (the beta client only knows .beta URIs); the prod->prod branch uses the prod
// scheme. Aligning this to io.github.zaentrum.chino later needs that URI added
// to the Keycloak client first.
val redirectBaseProd = if (prodPointsToBeta) "cloud.nalet.chino.mobile.beta" else "cloud.nalet.chino"

// Build-flavor server defaults. The neutral self-host client resolves its live
// server (API base + OIDC issuer/client) from the persisted ServerConfig set
// via the in-app Add-Server flow, so these are only non-UI fallbacks before a
// server is connected. The committed default is EMPTY — an operator building
// their own distribution can inject real values without code changes via
// gradle project properties (-PbetaApiBaseUrl=..., or gradle.properties /
// ~/.gradle/gradle.properties / ORG_GRADLE_PROJECT_* env vars).
fun prop(name: String): String = (project.findProperty(name) as? String) ?: ""
val betaApiBaseUrl = prop("betaApiBaseUrl")
val prodApiBaseUrl = prop("prodApiBaseUrl")
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
        minSdk = 24
        targetSdk = 35
        versionCode = ciVersionCode ?: 1
        versionName = "0.1.0"
        // AndroidJUnitRunner — invoked by `am instrument` to enumerate +
        // execute @Test methods inside the androidInstrumentedTest APK.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    flavorDimensions += "env"
    productFlavors {
        create("beta") {
            dimension = "env"
            applicationId = "$chinoAppId.mobile.beta"
            resValue("string", "app_name", "Chino Beta")
            buildConfigField("String", "FLAVOR_NAME", "\"beta\"")
            buildConfigField("String", "API_BASE_URL", "\"$betaApiBaseUrl\"")
            // Add-Server prefill: BLANK on every flavor so beta + prod behave
            // identically — neutral self-host client, field starts empty with a
            // generic placeholder, no baked operator URL. Separate from
            // API_BASE_URL (the internal non-UI fallback), on purpose.
            buildConfigField("String", "SERVER_PRESET", "\"\"")
            buildConfigField("String", "OIDC_ISSUER", "\"$oidcIssuer\"")
            buildConfigField("String", "OIDC_CLIENT_ID", "\"chino-mobile-beta\"")
            buildConfigField("String", "DISPLAY_NAME", "\"Chino Beta\"")
            // OIDC redirect scheme base; the app appends ".debug" at runtime for
            // debug builds (and the release manifest scheme is set in onVariants).
            buildConfigField("String", "OIDC_REDIRECT_BASE", "\"cloud.nalet.chino.mobile.beta\"")
            // Debug-build redirect scheme registered on RedirectUriReceiverActivity
            // (release is overridden per-variant in androidComponents below). The
            // Keycloak client lists both the .beta and .beta.debug schemes.
            manifestPlaceholders["appAuthRedirectScheme"] = "cloud.nalet.chino.mobile.beta.debug"
        }
        create("prod") {
            dimension = "env"
            // Unified Play listing id, shared with chino-androidtv's prod AAB.
            applicationId = chinoAppId
            resValue("string", "app_name", "Chino")
            buildConfigField("String", "FLAVOR_NAME", "\"prod\"")
            // With -PprodPointsToBeta=true the prod app uses the beta backend +
            // beta OIDC client (e.g. before a prod backend is live). Both base
            // URLs default to EMPTY and are injected by the operator's build via
            // -PprodApiBaseUrl / -PbetaApiBaseUrl. The neutral client resolves
            // its live server from the in-app Add-Server flow anyway.
            val prodApi = if (prodPointsToBeta) betaApiBaseUrl else prodApiBaseUrl
            val prodClient = if (prodPointsToBeta) "chino-mobile-beta" else "chino"
            buildConfigField("String", "API_BASE_URL", "\"$prodApi\"")
            // Neutral store build: NO pre-typed operator URL in the Add-Server
            // field. Blank => empty field + neutral placeholder + no suggestion
            // chip. API_BASE_URL above stays as the internal non-UI fallback.
            buildConfigField("String", "SERVER_PRESET", "\"\"")
            buildConfigField("String", "OIDC_ISSUER", "\"$oidcIssuer\"")
            buildConfigField("String", "OIDC_CLIENT_ID", "\"$prodClient\"")
            buildConfigField("String", "DISPLAY_NAME", "\"Chino\"")
            // Redirect scheme follows the OIDC client: pointing prod at beta means
            // using the beta client, so the redirect scheme must be the beta one
            // (the prod app id is NOT registered on the beta client). The app
            // appends ".debug" at runtime for debug builds; release is set below.
            buildConfigField("String", "OIDC_REDIRECT_BASE", "\"$redirectBaseProd\"")
            manifestPlaceholders["appAuthRedirectScheme"] = "$redirectBaseProd.debug"
        }
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
        // Release builds have no .debug suffix, so set the RedirectUriReceiver
        // scheme to the redirect base for the variant's flavor. For prod that
        // base follows prodPointsToBeta (the beta client only knows .beta URIs).
        // Debug builds keep the flavor placeholder (base + ".debug").
        if (variant.buildType == "release") {
            val base = if (variant.flavorName == "beta") "cloud.nalet.chino.mobile.beta" else redirectBaseProd
            variant.manifestPlaceholders.put("appAuthRedirectScheme", base)
        }
    }
}

package cloud.nalet.chino.mobile.android

import android.app.Activity
import android.app.Application
import android.os.Build
import android.os.Bundle
import cloud.nalet.chino.mobile.AppConfig
import cloud.nalet.chino.mobile.data.AppContainer
import cloud.nalet.chino.mobile.data.PlatformServerConfigStoreFactory
import cloud.nalet.chino.mobile.data.PlatformSettingsStoreFactory
import cloud.nalet.chino.mobile.data.auth.PlatformAccountStoreFactory
import cloud.nalet.chino.mobile.data.auth.PlatformTokenStoreFactory
import cloud.nalet.chino.mobile.feedback.CurrentActivity
import cloud.nalet.chino.mobile.feedback.FilePendingReportStore
import cloud.nalet.chino.mobile.feedback.bugFingerprint
import java.io.File

class ChinoMobileApplication : Application() {
    lateinit var container: AppContainer
        private set

    /** Crash-report queue at filesDir/bug_reports — written synchronously by
     *  the uncaught-exception handler below, drained by BugReporter.flushPending
     *  on the next signed-in launch (the shell fires it). One instance shared
     *  with AppContainer so writer + reader agree on the directory. */
    private val pendingReports by lazy { FilePendingReportStore(File(filesDir, "bug_reports")) }

    override fun onCreate() {
        super.onCreate()
        // Track the resumed Activity for feedback screenshots (PixelCopy
        // needs the live window). Registered before any Activity exists.
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) = CurrentActivity.onResumed(activity)
            override fun onActivityPaused(activity: Activity) = CurrentActivity.onPaused(activity)
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
        installCrashReporter()
        container = buildContainer()
    }

    /** Rebuild the DI graph from scratch — called after the user connects to
     *  or changes a server so the new container's lazy clients (OIDC / Ktor /
     *  config) re-read the just-saved [cloud.nalet.chino.mobile.data.ServerConfig].
     *  The old container's HTTP clients are dropped with it (GC'd); MainActivity
     *  recreates itself right after so the new graph is what the recomposed App
     *  sees. Mirrors chino-androidtv's process-restart-on-connect intent without
     *  killing the process. */
    fun rebuildContainer(): AppContainer {
        container = buildContainer()
        return container
    }

    /** Persist a crash as a pending bug report, then delegate to the previous
     *  handler (which shows the system crash dialog / kills the process). The
     *  write is SYNCHRONOUS on purpose — the process dies right after, so a
     *  coroutine would never run. The report is submitted on the NEXT launch,
     *  once a bearer is available (BugReporter.flushPending keeps the file
     *  when submission fails, e.g. crash-before-first-login). */
    private fun installCrashReporter() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val stack = throwable.stackTraceToString()
                pendingReports.writeCrashSync(
                    description = stack,
                    fingerprint = bugFingerprint(
                        name = throwable.javaClass.name,
                        message = throwable.message,
                        stack = stack,
                    ),
                    context = deviceStaticContext() + mapOf("thread" to thread.name),
                )
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun buildContainer(): AppContainer {
        val cfg = AppConfig(
            flavor = if (BuildConfig.FLAVOR_NAME == "prod") AppConfig.Flavor.PROD else AppConfig.Flavor.BETA,
            apiBaseUrl = BuildConfig.API_BASE_URL,
            oidcIssuer = BuildConfig.OIDC_ISSUER,
            oidcClientId = BuildConfig.OIDC_CLIENT_ID,
            displayName = BuildConfig.DISPLAY_NAME,
            // Add-Server prefill / suggestion: beta = beta origin, prod = "" (the
            // neutral store build ships no pre-typed operator URL). Separate from
            // API_BASE_URL, which stays the internal non-UI fallback.
            serverPreset = BuildConfig.SERVER_PRESET,
            // Redirect scheme = flavor base + the runtime ".debug" suffix (debug
            // builds carry .debug on the applicationId). Kept in sync with the
            // appAuthRedirectScheme manifest placeholder.
            redirectScheme = BuildConfig.OIDC_REDIRECT_BASE +
                if (BuildConfig.APPLICATION_ID.endsWith(".debug")) ".debug" else "",
        )
        return AppContainer(
            // Build-flavor defaults only — the live server (apiBaseUrl + OIDC
            // issuer/client) is resolved from the persisted ServerConfig.
            buildConfig = cfg,
            accountStoreFactory = PlatformAccountStoreFactory(this),
            tokenStoreFactory = PlatformTokenStoreFactory(this),
            settingsStoreFactory = PlatformSettingsStoreFactory(this),
            serverConfigStoreFactory = PlatformServerConfigStoreFactory(this),
            deviceStaticContext = deviceStaticContext(),
            pendingReportStore = pendingReports,
        )
    }

    /** Static device/app fields stamped on every telemetry event AND bug
     *  report — none of these change for the life of the process so building
     *  the map once per call site is the cheapest path. Shared between
     *  [buildContainer] and the crash handler (which can't reach the
     *  container — it may fire before/while the container is being built). */
    private fun deviceStaticContext(): Map<String, String> = mapOf(
        "device_model" to Build.MODEL,
        "device_manufacturer" to Build.MANUFACTURER,
        "device_brand" to Build.BRAND,
        "device_abi" to (Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"),
        "device_64bit" to Build.SUPPORTED_64_BIT_ABIS.isNotEmpty().toString(),
        "os_version" to Build.VERSION.RELEASE,
        "os_sdk_int" to Build.VERSION.SDK_INT.toString(),
        "app_version" to BuildConfig.VERSION_NAME,
        "app_version_code" to BuildConfig.VERSION_CODE.toString(),
        "app_flavor" to BuildConfig.FLAVOR_NAME,
        "app_build_type" to BuildConfig.BUILD_TYPE,
        "client" to "chino-mobile-android",
    )
}

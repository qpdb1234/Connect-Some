/**
 * The controlled-side helper of the "Android device" protocol.
 *
 * This is deliberately *not* a normal Android application. Its sources are an
 * adapted copy of Easycontrol_For_Car's `server` module (GPL-3.0): they are
 * compiled into an APK, the APK is renamed to `android_server.jar` and pushed to
 * `/data/local/tmp/` on the target device, where `app_process` loads it as a
 * plain JVM classpath. Nothing here is ever installed by a package manager, so:
 *
 *  - the manifest is empty (no components, no permissions - the process runs
 *    with the shell UID and reaches system services through reflection);
 *  - only the `debug` variant is used, because minification/obfuscation would
 *    destroy the reflective lookups and the AIDL stubs;
 *  - no library dependencies: dex from a standalone jar cannot see androidx
 *    classes on the target device.
 *
 * See [com.limelight.adb.Adb] on the client side for the startup commands.
 */
plugins {
    id("com.android.application")
}

android {
    namespace = "com.limelight.androidserver"

    compileSdk = 37

    defaultConfig {
        applicationId = "com.limelight.androidserver"

        // Kept far below the app's minSdk: this code must also run on old
        // controlled devices. It only uses APIs guarded by Build.VERSION checks.
        minSdk = 21
        targetSdk = 37

        // MUST stay in sync with app/build.gradle.kts. The client embeds this
        // number into the pushed file name, so an app upgrade automatically
        // replaces an outdated server jar on the device.
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        // Only `debug` is ever packaged: no R8, deterministic output path.
        debug {
            isDebuggable = false
        }
        release {
            // Explicitly not minified for the same reason as above; the release
            // variant is never consumed, it exists only because AGP requires it.
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    buildFeatures {
        // The three AIDL stubs under src/main/aidl (clipboard / rotation /
        // fold watchers) are real framework interfaces. AGP 9 dropped the
        // global `android.defaults.buildfeatures.aidl` property but the
        // per-module switch below is still the supported way to enable it.
        aidl = true
    }

    lint {
        // Not installable, so the usual manifest/component checks do not apply.
        disable += listOf(
            "ManifestOrder",
            "MissingApplicationIcon",
            "AllowBackup",
            "UnusedResources",
            "ExpiredTargetSdkVersion",
        )
    }
}

// No dependencies on purpose - see the class comment.

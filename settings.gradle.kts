pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Only for libadb-android (ADB wireless-debugging pairing, GPL/LGPL-compatible),
        // which is published on JitPack rather than Maven Central.
        maven("https://jitpack.io")
    }
}

rootProject.name = "Connect_Some"
include(":app")

// The controlled-side (Android device) helper. It is *not* an installable app: it
// is built into an unsigned APK whose classes.dex is loaded by `app_process` on the
// target device. See androidserver/build.gradle.kts and the `packageAndroidServerJar`
// task in app/build.gradle.kts.
include(":androidserver")

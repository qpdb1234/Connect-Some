import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.plugin.parcelize")
    id("com.android.legacy-kapt")
}

android {
    namespace = "com.limelight"

    compileSdk = 37
    ndkVersion = "28.2.13676358"

    defaultConfig {
        // Rebranded application id matching the "Connect Some" app name. The code
        // namespace stays `com.limelight` (R class, JNI symbols Java_com_limelight_*
        // depend on it), only the installed package identifier changes.
        applicationId = "com.connectsome.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Moonlight uses ROOT_BUILD to distinguish root/nonRoot flavors
        buildConfigField("boolean", "ROOT_BUILD", "false")

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DANDROID_ARM_NEON=TRUE"
                )
            }
        }

        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86_64")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
        dataBinding = true
        buildConfig = true
    }

    // The clipboard bridge speaks a byte protocol with no Android in it, so its tests
    // run on the JVM. `Log` is the only framework call left, and it may return defaults.
    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    // AndroidX / Material (from AVNC)
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.3")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.fragment:fragment-ktx:1.8.9")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.dynamicanimation:dynamicanimation-ktx:1.1.0")
    implementation("androidx.biometric:biometric-ktx:1.2.0-alpha05")
    implementation("androidx.drawerlayout:drawerlayout:1.2.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.9.3")

    // Room
    val roomVersion = "2.7.2"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    kapt("androidx.room:room-compiler:$roomVersion")

    // Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")

    // Moonlight protocol dependencies
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")
    implementation("org.jcodec:jcodec:0.2.5")
    implementation("org.jmdns:jmdns:3.5.9")

    // Android-device protocol: ADB wireless-debugging pairing (SPAKE2 + TLS-PSK).
    // JitPack artifact, LGPL-3.0 - see com.limelight.adb.AdbPairingPort. Its
    // transitive bcprov-jdk15to18 is dropped: we already ship bcprov-jdk18on, and
    // two BouncyCastle providers would collide at dex time.
    implementation("com.github.MuntashirAkon:libadb-android:3.0.0") {
        exclude(group = "org.bouncycastle")
    }

    testImplementation("junit:junit:4.13.2")
}

/**************************************************************************
 * Android-device protocol: bundle the controlled-side helper as a raw resource
 *
 * `:androidserver` produces an APK whose classes.dex is the whole payload; an APK
 * is a zip, so `app_process` happily loads it under a `.jar` name once it has been
 * pushed to /data/local/tmp on the device. Copying it into res/raw is what makes
 * `R.raw.android_server` available to com.limelight.adb.Adb.
 *
 * Deliberately uses no AGP variant API (removed in AGP 9) - just a path glob on
 * the module's output directory.
 **************************************************************************/
tasks.register<Copy>("packageAndroidServerJar") {
    dependsOn(":androidserver:assembleDebug")
    from(rootProject.layout.projectDirectory.dir("androidserver/build/outputs/apk/debug")) {
        include("*-debug.apk")
    }
    into(layout.projectDirectory.dir("src/main/res/raw"))
    rename { "android_server.jar" }
}

tasks.named("preBuild") {
    dependsOn("packageAndroidServerJar")
}

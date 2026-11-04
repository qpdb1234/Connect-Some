# Integration Guide – Moonlight Protocol into AVNC UI

## 1. Project Layout (recommended)

```
Connect_Some/
├── app/
│   ├── build.gradle.kts                 # applicationId = "com.limelight"
│   ├── CMakeLists.txt                   # merged native
│   └── src/main/
│       ├── AndroidManifest.xml          # Moonlight package + AVNC activities
│       ├── java/com/limelight/
│       │   ├── stream/                  # ★ NEW core client
│       │   │   ├── StreamClient.kt
│       │   │   ├── MoonlightStreamClient.kt
│       │   │   ├── StreamConfig.kt
│       │   │   └── ...
│       │   ├── ui/                      # copied & package-renamed from AVNC
│       │   ├── viewmodel/               # adapted VncViewModel → StreamViewModel
│       │   ├── session/                 # RemoteSession + Messenger adapted
│       │   ├── model/                   # ServerProfile extended
│       │   ├── nvstream/                # copied from Moonlight (Java)
│       │   └── binding/                 # Moonlight video/audio/input/crypto
│       ├── cpp/
│       │   ├── moonlight-common-c/      # git submodule
│       │   └── CMakeLists.txt
│       └── res/                         # AVNC layouts + Moonlight icons/mipmap
├── gradle/
└── settings.gradle.kts
```

## 2. Package rename

Global replace in all Kotlin/Java/XML:
- `com.gaurav.avnc` → `com.limelight`
- Keep Moonlight's original `com.limelight.nvstream.*` packages.

## 3. Core replacement files (already generated in this project)

- `StreamClient.kt` – interface aligned with original VncClient
- `MoonlightStreamClient.kt` – concrete implementation using NvConnection
- `StreamConfig.kt` – resolution / bitrate / HDR / audio etc.
- `PointerButton.kt` – pointer-button masks (bit-compatible with AVNC)
- Extended `ServerProfile`
- `session/Messenger.kt` – AVNC Messenger re-pointed at `StreamClient`
- `session/RemoteSession.kt` – session controller adapted to Moonlight's
  callback-driven model (the RFB receive loop is removed; terminal events arrive
  through `StreamClient.Observer.onConnectionTerminated`). The hosting ViewModel
  calls `onClientConnected()` / `onClientDisconnected()` in response to the
  client's `onConnectionStarted` / `onConnectionTerminated` callbacks.
- `util/WakeOnLAN.kt` – self-contained WoL magic-packet broadcaster

## 4. Remaining manual work after opening in Android Studio

1. Copy full AVNC `ui/`, `viewmodel/`, `util/`, `model/` and change package.
2. Copy Moonlight `nvstream/`, `binding/`, `computers/`, `discovery/` packages.
3. Merge CMakeLists.txt (link moonlight-common-c + any remaining AVNC native if SSH still needed).
4. Update Room schema / migrations for new ServerProfile fields.
5. Replace FrameView rendering path: from texture upload → Surface from MediaCodecDecoderRenderer.
6. Wire pairing PIN dialog and App selection UI (reuse AVNC dialogs).
7. Update strings.xml / themes to keep AVNC look while using Moonlight name.

## 5. Build command (after Android Studio setup)

```bash
./gradlew assembleRelease
# or
./gradlew assembleDebug
```

APK will appear in `app/build/outputs/apk/`.

## 6. Android-device protocol (third protocol)

Two new source areas, neither of which touches the AVNC UI layer:

```
Connect_Some/
├── androidserver/                       # ★ controlled-side helper (pure Java + AIDL)
│   ├── build.gradle.kts                 # com.android.application, buildFeatures.aidl = true
│   └── src/main/java|aidl/com/limelight/androidserver/...
└── app/src/main/
    ├── java/com/limelight/
    │   ├── adb/                         # ★ ADB host: channel, protocol, keys, USB/TCP
    │   └── vnc/AndroidClient.kt         # ★ VncClient implementation
    └── res/raw/android_server.jar       # generated, git-ignored
```

Build wiring:

- `settings.gradle.kts` includes `:androidserver` and adds `maven("https://jitpack.io")`
  for `libadb-android` (wireless-debugging pairing only).
- `:app` registers `packageAndroidServerJar`, hooked on `preBuild`: it depends on
  `:androidserver:assembleDebug`, copies `androidserver/build/outputs/apk/debug/*-debug.apk`
  to `app/src/main/res/raw/android_server.jar`. No AGP variant API is used - that whole
  surface was removed in AGP 9.
- Because `preBuild` depends on that copy task and the copy task depends on
  `:androidserver:assembleDebug`, a plain `./gradlew :app:assembleDebug` always has
  `R.raw.android_server` present at compile time - even on a fresh clone, where the
  jar is not in git. To produce it on its own: `./gradlew :androidserver:assembleDebug`.

Licensing: `:androidserver` and `com.limelight.adb` are adapted from
Easycontrol_For_Car (GPL-3.0); each file keeps the original copyright line plus an
"adapted from" note. `libadb-android` is LGPL-3.0. Both are listed in
`docs/ThirdPartyLicenses.md`.

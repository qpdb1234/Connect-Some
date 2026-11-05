# Connect Some

[![build](https://github.com/qpdb1234/Connect_Some/actions/workflows/android.yml/badge.svg)](https://github.com/qpdb1234/Connect_Some/actions/workflows/android.yml)
[![release](https://img.shields.io/github/v/release/qpdb1234/Connect_Some)](https://github.com/qpdb1234/Connect_Some/releases/latest)
[![license](https://img.shields.io/badge/license-GPL--3.0--or--later-blue)](COPYING.txt)

An Android client for working with another machine from a phone. One app speaks three
protocols:

- **RFB (VNC)** - any standard VNC server, through `libnative-vnc.so`, with per-server
  authentication (automatic, none, VNC password, AnonTLS, VeNCrypt, Apple screen
  sharing) and an UltraVNC repeater in front if needed.
- **GameStream (NVStream)** - the Moonlight client, for a PC running Sunshine: PIN
  pairing, then the desktop or any single app on that PC.
- **ADB (scrcpy)** - mirrors and drives another Android device over wireless debugging
  or a USB-OTG cable. Nothing has to be installed on that device; a helper is pushed to
  `/data/local/tmp` and run from there.

All three share one set of gestures, one keyboard, one set of overlays, one set of
settings, and the same picture controls, and each saved server keeps its own picture and
input settings. On top of the clients there is LAN discovery (mDNS browse plus active
probing, subnet walk, neighbour table, NetBIOS, MAC vendors), Wake-on-LAN, LocalSend file
transfer with downloadable links, a floating mini window, an assist ball and a drawer.

- **Application id**: `com.connectsome.app` (the code namespace stays `com.limelight`,
  which the `R` class and the `Java_com_limelight_*` JNI symbols depend on)
- **Min / target SDK**: 26 / 37
- **License**: GPL-3.0-or-later (`LICENSE`, `COPYING.txt`)

## Where this came from

Connect Some is built by merging several existing open-source projects rather than by
reimplementing their protocols. Every adapted file keeps the upstream copyright line and
an "adapted from" note; the full notices are in [`docs/ThirdPartyLicenses.md`](docs/ThirdPartyLicenses.md).

| Upstream | License | What it supplies here |
| --- | --- | --- |
| [AVNC](https://github.com/gujjwal00/avnc) (Gaurav Ujjwal) | GPL-3.0-or-later | RFB client, server-profile model, most of the settings UI, Material 3 look |
| [Moonlight](https://github.com/moonlight-stream/moonlight-android) + [moonlight-common-c](https://github.com/moonlight-stream/moonlight-common-c) | GPL-3.0 (moonlight-common-c adds a section 7 app-store distribution exception) | GameStream client, decoder path, controller model |
| [Easycontrol_For_Car](https://github.com/Eiyooooo/Easycontrol_For_Car) | GPL-3.0 | `:androidserver` helper and `com.limelight.adb` (scrcpy-over-ADB transport) |
| [libadb-android](https://github.com/MuntashirAkon/libadb-android) | LGPL-3.0 | SPAKE2 + TLS-PSK wireless-debugging pairing |
| [libvncserver](https://github.com/gujjwal00/libvncserver) (fork, branch `avnc`) | GPL-2.0-or-later | LibVNCClient, statically linked into `libnative-vnc.so` |
| [libjpeg-turbo](https://github.com/libjpeg-turbo/libjpeg-turbo) | IJG / zlib / BSD-style | the JPEG codec behind the VNC Tight encoding (prebuilt static archives) |
| [csicar/Ning](https://github.com/csicar/Ning) | LGPL-3.0-or-later | active mDNS probing, the neighbour/hostname scan structure, the MAC-vendor database |
| [Duorem](https://github.com/fralik/Duorem) | GPL-3.0-or-later | broadcast-address derivation and the NetBIOS node-status idea |
| [Mi-Freeform](https://github.com/qpdb1234/Mi-Freeform) | (design reference only) | the mini window's card, top-bar slots and defaults - no code is linked |

Written for this app on top of them: LocalSend (with favorites, queues and download
links), the mini window, the assist ball and the drawer, the pointer marker, the link
server, and most of the discovery pipeline.

## Building

### Requirements

| Component | Version used by this project |
| --- | --- |
| Android Gradle Plugin | 9.2.1 |
| Gradle (wrapper) | 9.5.0 |
| Kotlin | 2.2.20 |
| JDK | 17 |
| `compileSdk` / `targetSdk` | 37 |
| `minSdk` | 26 |
| NDK | 28.2.13676358 |
| CMake | 3.22.1 |
| ABIs | armeabi-v7a, arm64-v8a, x86_64 |

### Steps

1. Clone with the submodules - `moonlight-common-c` (and its own `enet` / `nanors`) and
   `extern/libvncserver` are what the two native libraries compile against:

   ```bash
   git clone --recurse-submodules <this repo>
   # or, after a plain clone:
   git submodule update --init --recursive
   ```

2. Open the project in Android Studio and let it write `local.properties`, or build from
   the command line with `ANDROID_HOME` set. `local.properties` is machine-local and is
   not committed.

   Installing the SDK by hand, note that API 37 is published as `android-37.0` - the
   minor version is part of the package id, so `platforms;android-37` does not exist:

   ```bash
   sdkmanager --install "platforms;android-37.0" "build-tools;36.0.0" \
     "cmake;3.22.1" "ndk;28.2.13676358"
   ```

3. Build:

   ```bash
   ./gradlew :app:assembleDebug
   ```

   The first build also produces the controlled-side helper: `:androidserver:assembleDebug`
   runs, and `packageAndroidServerJar` copies its APK (a zip with a `classes.dex`, which is
   what `app_process` accepts) to `app/src/main/res/raw/android_server.jar`. That file is a
   build product and is git-ignored.

4. Optional - the VNC image-quality setting rides on the Tight encoding, which LibVNCClient
   only compiles in when libjpeg is available. The prebuilt archives for every ABI are in
   `extern/jpeg-prebuilt/<abi>` and are used as-is; to rebuild them from a libjpeg-turbo
   checkout:

   ```powershell
   powershell -ExecutionPolicy Bypass -File extern\build-libjpeg-turbo.ps1
   ```

   Skipping this never breaks the build - `app/src/main/cpp/CMakeLists.txt` says so on the
   affected screen and the client falls back to the JPEG-free encoders.

`scripts/check-native.ps1` prints which native parts the current tree can build.

`.github/workflows/android.yml` runs `:app:assembleDebug` on every push to `main` and on
pull requests: it initializes the two submodules, installs the pinned platform, NDK and
CMake, builds all three ABIs, checks that each ABI's native libraries and the
`android_server.jar` payload ended up in the APK, and uploads it as a workflow artifact.
The artifact is a debug build signed with the standard Android debug key - it is there to
prove the tree builds, not a release channel.

`.github/workflows/release.yml` runs on a `v*` tag: it builds that tag with the same
composite actions in `.github/actions`, and publishes the resulting APK on the tag as a
GitHub Release. Releases are signed with the standard Android debug key, because no
release signing key is kept in this repository - see the notes of any release.

## How the pieces fit

`docs/Architecture.md` covers the layering; the short version is that AVNC's
`VncClient` became a `StreamClient` interface with three implementations
(`RFB`, `GameStream`, `ADB`), and everything above it - activities, gestures, overlays,
preferences - is shared.

### Clipboard

What the per-profile `clipboard_sync` switch can do depends on the protocol:

| Protocol | Remote → phone | Phone → remote |
| --- | --- | --- |
| VNC (RFB) | yes | yes |
| Android device (ADB) | yes | yes |
| GameStream (Sunshine / GFE) | **no - the protocol has no clipboard channel** | yes, injected as typed text |

**VNC.** RFB specifies `ServerCutText` / `ClientCutText` as Latin-1, but Windows servers
fill them with the clipboard's ANSI codepage bytes, so CJK payloads arrive as mojibake.
The client therefore decodes a non-UTF-8 payload as UTF-8 first (many servers ignore the
type and send UTF-8), then - only when the phone's locale is Chinese/Japanese/Korean,
since the bytes alone cannot tell `Grüße` from a legal GBK pair - as GBK / Big5 /
Shift_JIS / EUC-KR, and finally as the promised Latin-1. Text sent the other way uses the
same order. Servers that negotiate the extended (RFB 3.8 / TightVNC) clipboard get UTF-8
directly. Settings → Servers carries a **Strict Latin-1 clipboard** switch for the rare
server these guesses read wrong; it drops the guessing in both directions and is read once
per connection.

**Android device.** The helper watches the controlled device's clipboard through the
hidden `IOnPrimaryClipChangedListener` and pushes every change as UTF-8 (5000-byte cap);
no encoding guessing is needed.

**GameStream.** `moonlight-common-c` carries video, audio, input and statistics - not
clipboard - so a copy on the PC cannot reach the phone. The phone's clipboard can still be
delivered: the clipboard button sends it as Unicode text input.

### Audio and the volume keys

The volume rocker always belongs to the phone. All three protocols can map
`VOLUME_UP/DOWN/MUTE` to a remote key, and forwarding it consumes the event - that is what
made the phone's own volume unusable inside a session. `InputHandler` refuses those three
key codes before any protocol sees them, and `VncActivity` pins the keys to the media
stream.

| Protocol | Remote audio | What the keys move |
| --- | --- | --- |
| VNC (RFB) | none | media volume |
| GameStream | `USAGE_GAME` | media volume (the stream's own volume) |
| Android device | `USAGE_MEDIA` | media volume |

No protocol reports the host's volume or mute state, so a muted PC keeps pushing
full-level audio. The toolbar drawer therefore carries a speaker button that turns *this
phone's* playback down to zero (`VncClient.audioMuted`, applied as an `AudioTrack` volume
of 0) and back; the choice is remembered and re-applied before every connection. VNC has
no audio channel, so the button is hidden there.

### The Android-device protocol (`:androidserver`)

The third protocol needs code that runs **on the device being controlled**, and that code
cannot be an installed app: the screen capture and input injection used here rely on
hidden APIs reachable only from the shell uid.

1. `:androidserver` compiles the helper (`com.limelight.androidserver`) with AIDL enabled
   and no dependencies at all.
2. `packageAndroidServerJar` (wired to `preBuild`) drops its debug APK into
   `app/src/main/res/raw/android_server.jar`.
3. At connect time `com.limelight.adb.Adb` pushes that resource to
   `/data/local/tmp/connectsome_server_<versionCode>.jar` over ADB's `sync:` service,
   starts the resident RPC process once and the per-session encoder process on every
   connect, then opens two `localabstract:connectsome_scrcpy` streams - one for video, one
   for control events.

Transport is a USB-OTG cable (`android.hardware.usb.host`, no pairing needed) or the
network: an already-authorised `host:5555`, or Android 11+ wireless-debugging pairing
through `libadb-android` (used only by `com.limelight.adb.AdbPairingPort`).

## Repository layout

```
.github/                 workflows, and the composite actions they share
app/                     the app itself (Kotlin/Java) + JNI glue and CMakeLists
  src/main/cpp/          native build: RFB client, moonlight-core, openssl/opus archives
  src/main/res/values*/  23 locales
androidserver/           the controlled-side helper, compiled into res/raw/android_server.jar
extern/                  libvncserver (submodule), jpeg-prebuilt/, build-libjpeg-turbo.ps1
docs/                    Architecture.md, ThirdPartyLicenses.md
scripts/                 check-native.ps1
```

## License

Connect Some is free software: you can redistribute it and/or modify it under the terms of
the GNU General Public License as published by the Free Software Foundation, either
version 3 of the License, or (at your option) any later version. It is distributed in the
hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See `COPYING.txt` / `LICENSE` for the
full text.

Because parts of it are adapted from GPL, LGPL and GPL-2.0-or-later works, the combined
work stays under GPL-3.0-or-later; the notices each of those licenses require for a binary
distribution, together with what was changed and why, are collected in
[`docs/ThirdPartyLicenses.md`](docs/ThirdPartyLicenses.md).

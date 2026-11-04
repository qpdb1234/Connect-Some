# Architecture – Connect_Some

## Goal
Keep **100% of AVNC’s UI, gestures, VirtualKeys, Profile management, SSH, WoL, PiP, clipboard**  
while replacing the transport/protocol layer with **Moonlight (GameStream + Sunshine)**.

## Layer mapping

| Layer              | Original AVNC              | Integrated (this project)          |
|--------------------|----------------------------|------------------------------------|
| UI                 | HomeActivity, VncActivity, FrameView, VirtualKeys | Same classes, package → com.limelight |
| ViewModel          | VncViewModel, HomeViewModel | StreamViewModel (thin adapter)     |
| Session            | RemoteSession + Messenger  | Same, now talks to StreamClient    |
| Client             | VncClient (LibVNCClient)   | **StreamClient / MoonlightStreamClient** |
| Protocol           | RFB                        | NvConnection + moonlight-common-c  |
| Video              | Software / texture upload  | MediaCodecDecoderRenderer → Surface|
| Discovery          | Zeroconf                   | Moonlight mDNS + Zeroconf fallback |
| Pairing            | (none)                     | PairingManager + PIN dialog        |

## Key design decision
All higher layers only depend on the `StreamClient` interface.  
This makes it possible to keep the entire AVNC experience while swapping the protocol.

## The three protocols
`ServerProfile.protocol` is read in exactly one place - `VncClientFactory` - which
returns one `VncClient` implementation. Nothing upstream of the factory asks "which
protocol is this?"; it asks for capabilities instead, all of which are declared on the
client itself (`vnc/ClientCapabilities.kt`):

| Capability | `RfbClientNative` | `GameStreamClient` | `AndroidClient` |
|---|---|---|---|
| wire | RFB via `libnative-vnc.so` | NvConnection / moonlight-common-c | ADB (USB-OTG or TCP) + `app_process` helper |
| `renderMode` | `PIXEL_BUFFER` | `SURFACE` | `SURFACE` |
| `ownsReceiveLoop` | `true` | `false` | `false` |
| `inputPath` | `RFB_KEYSYM` | `GS_SCANCODE` | `ANDROID_KEYCODE` |
| `touchPath` | `GESTURE` | `GESTURE` | `DIRECT` (multi-touch pass-through) |
| optional interface | - | `GameStreamExtras` | `AndroidControlExtras` + `VideoSink` |

Adding a fourth protocol therefore means one more factory branch plus one more class
that declares its capabilities; the UI, session and input layers keep working because
they only ever consult capabilities. Casts to an optional interface are written as
`client as? AndroidControlExtras`, so a client that lacks a feature degrades silently.

### Android-device protocol: the controlled side
`AndroidClient` speaks the scrcpy-derived control protocol used by
`Easycontrol_For_Car`. The peer code is not an installed APK on the target - it is
`:androidserver`'s debug APK, pushed to `/data/local/tmp` and run with `app_process`
so that it executes under the shell uid (required for the hidden-API screen capture
and input injection). `:app`'s `packageAndroidServerJar` Gradle task copies the module
output to `app/src/main/res/raw/android_server.jar`, which is what
`com.limelight.adb.Adb` reads out of `R.raw`.

The ADB host implementation lives in `com.limelight.adb` (channel, protocol,
key pair, USB/TCP transports, connection cache in `AdbManager`) and is the only place
that knows ADB exists; `AndroidClient` deals in streams, control packets and
`MediaCodec`.

## Build status of this skeleton
- Core interfaces and MoonlightStreamClient skeleton: **done**
- ServerProfile extension: **done**
- Session layer (`Messenger`, `RemoteSession`) adapted to the callback-driven
  `StreamClient` (RFB receive loop removed): **done**
- Self-contained Wake-on-LAN util: **done**
- Gradle / Manifest / package name: **done**
- `:androidserver` module + `packageAndroidServerJar` packaging: **done**
- ADB host (`com.limelight.adb`), `AndroidClient`, USB-OTG / TCP / wireless-pairing
  transports: **done**
- Full source copy of AVNC UI + Moonlight nvstream: **manual step** (see INTEGRATION.md)
- Native moonlight-common-c: **manual submodule**
- Final APK: build with Android Studio on a machine that has the SDK/NDK

# Third-party licenses

This app statically links code that is not covered by the project's own
`GPL-3.0-or-later` header. The texts and notices below are reproduced as those
licenses require for a binary distribution.

## libjpeg-turbo

`extern/jpeg-prebuilt/<abi>/lib/libjpeg.a` is a static build of
[libjpeg-turbo](https://github.com/libjpeg-turbo/libjpeg-turbo) **3.1.4**
produced by `extern/build-libjpeg-turbo.ps1`, and it is linked into
`libnative-vnc.so` together with LibVNCClient. It exists because the VNC image
quality setting is carried to the server by the Tight encoding, which LibVNCClient
only compiles in when libjpeg is available. No source file of libjpeg-turbo was
modified; only build options were selected (`-DENABLE_SHARED=0`,
`-DWITH_TURBOJPEG=0`, `-DWITH_JAVA=0`, `-DWITH_TOOLS=0`, `-DWITH_TESTS=0`).

**This software is based in part on the work of the Independent JPEG Group.**

The libjpeg API library is covered by the IJG license (text in
`README.ijg` of the source tree) and by the zlib license for its SIMD code; the
build system and the TurboJPEG API are covered by the Modified BSD License
below. The TurboJPEG API is not built and not used here - libvncserver ships its
own `src/common/turbojpeg.c` shim written against the classic libjpeg API - but
the BSD text is included anyway since it also covers the build system.

> Copyright (C) 2009-2026 D. R. Commander.  All Rights Reserved.<br>
> Copyright (C) 2015 Viktor Szathmáry.  All Rights Reserved.
>
> Redistribution and use in source and binary forms, with or without
> modification, are permitted provided that the following conditions are met:
>
> - Redistributions of source code must retain the above copyright notice,
>   this list of conditions and the following disclaimer.
> - Redistributions in binary form must reproduce the above copyright notice,
>   this list of conditions and the following disclaimer in the documentation
>   and/or other materials provided with the distribution.
> - Neither the name of the libjpeg-turbo Project nor the names of its
>   contributors may be used to endorse or promote products derived from this
>   software without specific prior written permission.
>
> THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS",
> AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
> IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
> ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT HOLDERS OR CONTRIBUTORS BE
> LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
> CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
> SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
> INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
> CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
> ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
> POSSIBILITY OF SUCH DAMAGE.

The complete upstream notice, including the explanation of why two licenses are
used, is in `extern/libjpeg-turbo/LICENSE.md` (and is installed next to each
archive as `extern/jpeg-prebuilt/<abi>/share/doc/libjpeg-turbo/LICENSE.md`).

## LibVNCClient (libvncserver)

`extern/libvncserver` is gujjwal00's fork of libvncserver, which AVNC also uses.
It is distributed under the GPL-2.0-or-later (see `extern/libvncserver/COPYING`)
with some files under a BSD-style license; it is linked into `libnative-vnc.so`
as a static library, which is why the app as a whole is GPL.

## Easycontrol_For_Car

The Android-device protocol ships a helper that runs on the *controlled* phone. Its
source is the `:androidserver` module (Java plus three AIDL files under `android.content`
and `android.view`) and the ADB host code in `app/src/main/java/com/limelight/adb`, both
adapted from [Easycontrol_For_Car](https://github.com/Eiyooooo/Easycontrol_For_Car),
Copyright (c) Eiyooooo, licensed under **GPL-3.0**.

Adaptations, none of which change the protocol on the wire:

- package `top.eiyooooo.easycontrol.server` → `com.limelight.androidserver`; the AIDL
  packages are deliberately left at `android.content` / `android.view` because the
  binder interface token has to match the framework's;
- the `LocalServerSocket` name `easycontrol_for_car_scrcpy` → `connectsome_scrcpy`, and
  the process-liveness `ps -ef | grep …` strings, which are how the helper finds itself;
- pushed-jar path `/data/local/tmp/easycontrol_for_car_server_*.jar` →
  `/data/local/tmp/connectsome_server_<versionCode>.jar`;
- the helper's other on-device strings, all of them names it gives things rather than
  values it sends: log tag `easycontrol_for_car` → `ConnectSomeServer` and log file
  `/data/local/tmp/easycontrol_for_car_log` → `/data/local/tmp/connectsome_log`, saved
  screen-off timeout `/data/local/tmp/easycontrol_for_car_ScreenOffTimeout` →
  `/data/local/tmp/connectsome_screen_off_timeout`, the virtual display and SurfaceControl
  display names (`Channel.createVirtualDisplay`, `helper/VideoEncode`) → `connectsome`, and
  the `ClipData` label on the clipboard bridge → `connectsome`. Renaming the tmp files is
  what forced the cleanup glob in `Adb.remoteJarGlob` to narrow from `connectsome_*` to
  `connectsome_server_*` - otherwise pushing a new jar would delete the log and the stored
  timeout next to it;
- the ADB host was rewritten in Kotlin and its base64/RSA plumbing re-pointed at this
  app's own key pair storage; SPAKE2 pairing is delegated to `libadb-android` instead of
  the upstream's `android.sun.security.x509` reflection, which is not available on
  Android.

One behavioral difference is worth stating outright. The upstream night-mode numbering
treats `2` as "on" and `1` as "off" in both its write path (`PACKET_NIGHT`) and its read
path (`/getNightMode`); `android.app.UiModeManager`, whose number the helper passes
through untouched, means the opposite (`MODE_NIGHT_YES = 1`, `MODE_NIGHT_NO = 2`). This
app follows the framework, so its night-mode button does what its label says - and, since
the value is now read back over the RPC, the button can start from the device's actual
mode instead of assuming light and alternating from there.

Every adapted file keeps the upstream copyright line and carries an "Adapted from
Easycontrol_For_Car" note under the `GPL-3.0-or-later` SPDX header. As GPL-3.0 code
combined into a GPL-3.0-or-later work, the resulting app remains under this project's
license; the source of the helper is in this repository (`:androidserver`), which is
what its distribution as `app/src/main/res/raw/android_server.jar` requires.

## libadb-android

`com.github.MuntashirAkon:libadb-android:3.0.0` (JitPack, **LGPL-3.0**) provides the
SPAKE2 + TLS-PSK wireless-debugging pairing used by `com.limelight.adb.AdbPairingPort`
- the only file that references it. Its transitive `bcprov-jdk15to18` is excluded
because this app already ships `bcprov-jdk18on`, and two BouncyCastle versions in one
dex would collide. Linking an LGPL library dynamically against a GPL program is
compatible; the LGPL text and its source-availability terms are upstream.

## csicar/Ning

Parts of the LAN scan come from [csicar/Ning](https://github.com/csicar/Ning)
(**LGPL-3.0-or-later**), Copyright (c) csicar:

- `com.limelight.util.MdnsProbeScanner` is adapted from Ning's `LowLevelMDnsScanner` -
  the active mDNS query, which is what lets a scan find peers on networks that swallow
  multicast announcements;
- `com.limelight.util.LanScanner` follows Ning's `scanner/InterfaceScanner.kt`,
  `PingScanner.kt`, `ArpScanner.kt` and `HostnameScanner.kt` in the way it divides into a
  subnet walk, a neighbour-table read and name resolution. The TCP liveness probe that
  replaces `isReachable`, the network binding, the command variants and the address shape
  checks are this app's;
- `assets/mac_devices.db` is the IEEE OUI export that Ning's `createMacVendorDB.go`
  generates, and `com.limelight.util.MacVendorLookup` opens it. The copy-then-open and the
  prefix cache in that file are this app's.

Each of those files names the upstream in its header. Using LGPL-3.0-or-later code inside
a GPL-3.0-or-later program is compatible, so the combined work stays under this project's
license; the LGPL text is at https://www.gnu.org/licenses/lgpl-3.0.txt .

## Duorem

Two small pieces of the discovery and wake-on-LAN help come from
[Duorem](https://github.com/fralik/Duorem) (**GPL-3.0-or-later**), whose sources are
carried as `Copyright (C) 2017 Vadim Frolov` plus `Copyright (C) 2009-2010 Aubort
Jean-Baptiste (Rorist)` under the line "Licensed under GNU's GPL 3 or any later version":

- the "get broadcast address" button of `com.limelight.ui.home.ProfileEditor` derives the
  broadcast the way Duorem's does, from the target's IPv4 address together with the prefix
  length of the local subnet that can reach it - the same input pair as
  `Network/NetInfo.getBroadcastFromIpAndCidr()`. The derivation itself is this app's
  `com.limelight.util.LocalNetwork`, and the comment over the button says which upstream
  it follows;
- `com.limelight.util.NetbiosLookup` takes from Duorem's `Network/NetBiosNodeStatus.java`
  the idea of asking a machine, over UDP 137, for its own hardware address. Its request
  flags, the record walk and the statistics-block check follow RFC 1002 rather than that
  file.

`com.limelight.util.NetbiosLookup` carries the attribution in its header.

## Referenced for design only

[Mi-Freeform](https://github.com/qpdb1234/Mi-Freeform) is the design reference for the
mini window: its rounded card, its three-slot top bar and the defaults those slots start
from. No Mi-Freeform source file is in this tree and none of its code is linked or
bundled, so there is no notice to reproduce here; `com.limelight.ui.vnc.MiniWindow` and
`com.limelight.ui.vnc.MiniBarAction` say in their comments which of their behavior follows
that app.

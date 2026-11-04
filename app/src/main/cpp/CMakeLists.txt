cmake_minimum_required(VERSION 3.22.1)
project(moonlight-avnc LANGUAGES C CXX)

# =============================================================================
#  moonlight-core native bridge build
#
#  Produces libmoonlight-core.so, which MoonBridge loads via
#  System.loadLibrary("moonlight-core").
#
#  This script is "source-aware":
#    * If the real moonlight-common-c submodule (plus the JNI glue and the
#      prebuilt openssl/opus static libs) is present, it compiles the genuine
#      streaming stack.
#    * Otherwise it falls back to a JNI_OnLoad-only placeholder so Gradle's
#      externalNativeBuild still configures; the Kotlin side then detects the
#      missing native methods (NativeLibraryProbe) and degrades gracefully.
#
#  The native tree is looked up in this order:
#    1. -DMOONLIGHT_CORE_DIR=<path> (explicit override)
#    2. <cpp>/moonlight-core            (if you copied the tree in)
#    3. <repo>/moonlight-android-master/.../src/main/jni/moonlight-core
#       (the source tree shipped alongside this project)
# =============================================================================

# ---- locate the moonlight-core JNI directory ------------------------------
set(_candidate_dirs "")
if(DEFINED MOONLIGHT_CORE_DIR)
    list(PREPEND _candidate_dirs "${MOONLIGHT_CORE_DIR}")
endif()
list(APPEND _candidate_dirs
        "${CMAKE_CURRENT_SOURCE_DIR}/moonlight-core"
        "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../moonlight-android-master/moonlight-android-master/app/src/main/jni/moonlight-core")

set(MOONLIGHT_CORE_RESOLVED "")
foreach(_c IN LISTS _candidate_dirs)
    get_filename_component(_abs "${_c}" ABSOLUTE BASE_DIR "${CMAKE_CURRENT_SOURCE_DIR}")
    if(IS_DIRECTORY "${_abs}")
        # Only treat a candidate as the resolved root if it actually contains
        # the real protocol sources (src/*.c + simplejni.c). This lets the
        # populated sibling tree win over an empty copied cpp/moonlight-core.
        file(GLOB _probe_src "${_abs}/moonlight-common-c/src/*.c")
        file(GLOB _probe_jni "${_abs}/simplejni.c")
        if(_probe_src AND _probe_jni)
            set(MOONLIGHT_CORE_RESOLVED "${_abs}")
            break()
        elseif(NOT MOONLIGHT_CORE_RESOLVED)
            # Remember the first merely-existing dir purely for diagnostics.
            set(MOONLIGHT_CORE_RESOLVED "")
            set(MOONLIGHT_CORE_FALLBACK "${_abs}")
        endif()
    endif()
endforeach()

# ---- decide whether the real sources are available ------------------------
if(MOONLIGHT_CORE_RESOLVED)
    set(MOONLIGHT_NATIVE_REAL TRUE)
    if(NOT DEFINED MOONLIGHT_CORE_FALLBACK)
        set(MOONLIGHT_CORE_FALLBACK "${MOONLIGHT_CORE_RESOLVED}")
    endif()
else()
    set(MOONLIGHT_NATIVE_REAL FALSE)
    # Use the fallback (empty copied tree) so the warning points at the folder
    # the user is most likely meant to populate.
    set(_commonc "${MOONLIGHT_CORE_FALLBACK}/moonlight-common-c")
endif()

# ---- openssl / opus prebuilt static libs (per ABI) ------------------------
if(MOONLIGHT_NATIVE_REAL)
    set(_commonc "${MOONLIGHT_CORE_RESOLVED}/moonlight-common-c")
    set(_prebuilt_openssl "${MOONLIGHT_CORE_RESOLVED}/openssl")
    set(_prebuilt_opus "${MOONLIGHT_CORE_RESOLVED}/opus")

    # NDK bundled cpufeatures library (provides <cpu-features.h> used by
    # moonlight-core/callbacks.c). The stock moonlight-android Android.mk
    # links it via `import-module android/cpufeatures`; we compile its source
    # directly so the CMake build is self-contained.
    if(DEFINED ANDROID_NDK)
        set(_ndk_root "${ANDROID_NDK}")
    elseif(DEFINED CMAKE_ANDROID_NDK)
        set(_ndk_root "${CMAKE_ANDROID_NDK}")
    endif()
    set(_cpufeatures "${_ndk_root}/sources/android/cpufeatures")
endif()

find_library(log-lib log)

if(MOONLIGHT_NATIVE_REAL)
    message(STATUS "[moonlight-avnc] Real native sources found at: ${MOONLIGHT_CORE_RESOLVED}")

    file(GLOB _cc_src     "${_commonc}/src/*.c")
    file(GLOB _cc_enet    "${_commonc}/enet/*.c")
    # nanors: mirror moonlight-android's Android.mk exactly. Only the 8-bit RS
    # path (rs.c) plus the generic OBLAS kernels are compiled. The rs16*/
    # oblas16* files use AArch64-only NEON intrinsics (vqtbl1q_u8 etc.) and are
    # deliberately NOT part of the upstream source list, so globbing them in
    # breaks the armeabi-v7a build.
    set(_cc_nanors
            "${_commonc}/nanors/rs.c"
            "${_commonc}/nanors/deps/obl/oblas_common.c"
            "${_commonc}/nanors/deps/obl/oblas_lite.c")

    add_library(moonlight-core SHARED
            ${_cc_src}
            ${_cc_enet}
            ${_cc_nanors}
            "${MOONLIGHT_CORE_RESOLVED}/simplejni.c"
            "${MOONLIGHT_CORE_RESOLVED}/callbacks.c"
            "${MOONLIGHT_CORE_RESOLVED}/minisdl.c"
            "${_cpufeatures}/cpu-features.c")

    target_include_directories(moonlight-core PRIVATE
            "${_commonc}/enet/include"
            "${_cpufeatures}"
            "${_commonc}/nanors/deps/obl"
            "${_commonc}/nanors"
            "${_commonc}/src"
            "${_prebuilt_openssl}/include"
            "${_prebuilt_opus}/include")

    target_compile_options(moonlight-core PRIVATE
            -DHAS_SOCKLEN_T=1 -DLC_ANDROID -DHAVE_CLOCK_GETTIME=1 -std=gnu11)

    # Mirror moonlight-android's Android.mk: hide all symbols pulled from the
    # prebuilt static archives (openssl/opus). Without this, referencing those
    # symbols from a shared library triggers illegal PC-relative relocations
    # (e.g. "R_AARCH64_ADR_PREL_PG_HI21 cannot be used against symbol ...")
    # because they would otherwise be preemptible in the exported .so.
    target_link_options(moonlight-core PRIVATE -Wl,--exclude-libs,ALL)

    # libcrypto (openssl) prebuilt per ABI
    add_library(crypto-prebuilt STATIC IMPORTED)
    set_target_properties(crypto-prebuilt PROPERTIES
            IMPORTED_LOCATION "${_prebuilt_openssl}/${ANDROID_ABI}/libcrypto.a")

    # libopus prebuilt per ABI
    add_library(opus-prebuilt STATIC IMPORTED)
    set_target_properties(opus-prebuilt PROPERTIES
            IMPORTED_LOCATION "${_prebuilt_opus}/${ANDROID_ABI}/libopus.a")

    target_link_libraries(moonlight-core PRIVATE
            opus-prebuilt crypto-prebuilt ${log-lib})
else()
    message(WARNING
            "[moonlight-avnc] moonlight-common-c sources NOT found; building "
            "placeholder libmoonlight-core.so. Copy the submodule into "
            "${_commonc} (or pass -DMOONLIGHT_CORE_DIR) to enable real streaming.")

    add_library(moonlight-core SHARED "${CMAKE_CURRENT_SOURCE_DIR}/placeholder.cpp")
    target_link_libraries(moonlight-core PRIVATE ${log-lib})
endif()

# =============================================================================
#  libvncclient (RFB / VNC) native bridge build
#
#  Produces libnative-vnc.so, which com.limelight.vnc.RfbClientNative loads via
#  System.loadLibrary("native-vnc").
#
#  Fully independent from the Moonlight target above: a missing/broken libvncserver
#  checkout must never take GameStream down with it, and vice versa. Same
#  source-aware trick as moonlight-core:
#    * libvncserver sources present -> build the real RFB client
#    * absent                       -> build a JNI_OnLoad-only placeholder and
#      warn; NativeLibraryProbe.isVncAvailable() then reports false and the UI
#      disables the VNC protocol option.
#
#  The tree is looked up in this order:
#    1. -DLIBVNC_SERVER_DIR=<path> (explicit override)
#    2. <cpp>/libvncserver            (if you copied the tree in)
#    3. <repo>/extern/libvncserver    (the git submodule location)
#
#  It must be the gujjwal00/libvncserver fork on its `avnc` branch: upstream
#  lacks pauseFramebufferUpdates, VerifyServerCertificate and the UTF-8
#  extended-clipboard hooks that native-vnc.cpp binds to.
# =============================================================================

set(_vnc_candidate_dirs "")
if(DEFINED LIBVNC_SERVER_DIR)
    list(PREPEND _vnc_candidate_dirs "${LIBVNC_SERVER_DIR}")
endif()
list(APPEND _vnc_candidate_dirs
        "${CMAKE_CURRENT_SOURCE_DIR}/libvncserver"
        "${CMAKE_CURRENT_SOURCE_DIR}/../../../../extern/libvncserver")

set(LIBVNC_RESOLVED "")
foreach(_c IN LISTS _vnc_candidate_dirs)
    get_filename_component(_abs "${_c}" ABSOLUTE BASE_DIR "${CMAKE_CURRENT_SOURCE_DIR}")
    # Require both the build script and the client sources, so a half-populated
    # submodule (empty folder, or server-only checkout) does not win.
    if(EXISTS "${_abs}/CMakeLists.txt" AND EXISTS "${_abs}/src/libvncclient/rfbclient.c")
        set(LIBVNC_RESOLVED "${_abs}")
        break()
    endif()
endforeach()

if(LIBVNC_RESOLVED)
    message(STATUS "[moonlight-avnc] libvncserver sources found at: ${LIBVNC_RESOLVED}")

    # zlib (Raw/ZRLE/zlibHex decoders) is the only external dependency we ask
    # for; the NDK sysroot ships libz. Look it up here because find_package()
    # results inside add_subdirectory() do not reach this scope, and
    # native-vnc.cpp must be told whether rfbclient.h exposes the LIBZ members.
    find_package(ZLIB)

    # libvncserver's own build script rewrites compile_commands.json into its
    # source directory through an `ALL` custom target; keep the export enabled
    # so that copy step cannot fail on a missing file.
    set(CMAKE_EXPORT_COMPILE_COMMANDS ON)

    # vncclient ends up as a static archive linked into a shared library, so
    # every object needs -fPIC (not guaranteed for armeabi-v7a by default).
    set(CMAKE_POSITION_INDEPENDENT_CODE ON)

    # Client only, static, and no optional backend we cannot ship here: no
    # TLS/VeNCrypt (no openssl/gnutls), no X11/SDL/GTK/Qt examples, no tests.
    #
    # libjpeg is opt-in and comes from extern/jpeg-prebuilt/<abi>, which
    # extern/build-libjpeg-turbo.ps1 produces. AVNC gets the same archive from
    # vcpkg (app/vcpkg.json lists libjpeg-turbo), and that is what enables the
    # Tight encoding in libvncclient: without it the decoder, the encoding token
    # and the rfbEncodingQualityLevel handshake are all compiled out, so the
    # image quality setting cannot reach the server. Preparing the cache entries
    # up front is deliberate - FindJPEG skips its search when JPEG_INCLUDE_DIR
    # and JPEG_LIBRARY already point at existing files, which is the only
    # reliable way to feed a cross-compiled archive past the NDK toolchain's
    # find-root-path restrictions.
    set(AVNC_JPEG_PREFIX
            "${CMAKE_CURRENT_SOURCE_DIR}/../../../../extern/jpeg-prebuilt/${ANDROID_ABI}")
    get_filename_component(AVNC_JPEG_PREFIX "${AVNC_JPEG_PREFIX}" ABSOLUTE)

    if(EXISTS "${AVNC_JPEG_PREFIX}/lib/libjpeg.a" AND
            EXISTS "${AVNC_JPEG_PREFIX}/include/jpeglib.h")
        set(JPEG_INCLUDE_DIR "${AVNC_JPEG_PREFIX}/include" CACHE PATH "libjpeg headers" FORCE)
        set(JPEG_LIBRARY "${AVNC_JPEG_PREFIX}/lib/libjpeg.a" CACHE FILEPATH "libjpeg" FORCE)
        set(AVNC_WITH_JPEG ON)
        message(STATUS "[moonlight-avnc] Tight encoding enabled (libjpeg: ${AVNC_JPEG_PREFIX})")
    else()
        unset(JPEG_INCLUDE_DIR CACHE)
        unset(JPEG_LIBRARY CACHE)
        set(AVNC_WITH_JPEG OFF)
        message(WARNING
                "[moonlight-avnc] no libjpeg for ${ANDROID_ABI} in "
                "extern/jpeg-prebuilt; libvncclient will be built WITHOUT the "
                "Tight encoding, and the image quality setting will fall back to "
                "ZYWRLE. Run extern/build-libjpeg-turbo.ps1 to fix this.")
    endif()

    set(WITH_LIBVNCSERVER           OFF CACHE BOOL "Build libvncserver" FORCE)
    set(WITH_LIBVNCCLIENT           ON  CACHE BOOL "Build libvncclient" FORCE)
    set(BUILD_SHARED_LIBS           OFF CACHE BOOL "Build shared libraries" FORCE)
    set(WITH_ZLIB                   ON  CACHE BOOL "Use zlib" FORCE)
    set(WITH_LZO                    OFF CACHE BOOL "Use system LZO (bundled miniLZO is used instead)" FORCE)
    set(WITH_JPEG                   ${AVNC_WITH_JPEG} CACHE BOOL "Use libjpeg" FORCE)
    set(WITH_PNG                    OFF CACHE BOOL "Use libpng" FORCE)
    set(WITH_SDL                    OFF CACHE BOOL "Use SDL" FORCE)
    set(WITH_GTK                    OFF CACHE BOOL "Use GTK" FORCE)
    set(WITH_QT                     OFF CACHE BOOL "Build the Qt client" FORCE)
    set(WITH_LIBSSHTUNNEL           OFF CACHE BOOL "Use libsshtunnel" FORCE)
    set(WITH_GNUTLS                 OFF CACHE BOOL "Use GnuTLS" FORCE)
    set(WITH_OPENSSL                OFF CACHE BOOL "Use OpenSSL" FORCE)
    set(WITH_GCRYPT                 OFF CACHE BOOL "Use Libgcrypt" FORCE)
    set(WITH_SASL                   OFF CACHE BOOL "Use SASL" FORCE)
    set(WITH_SYSTEMD                OFF CACHE BOOL "Use libsystemd" FORCE)
    set(WITH_FFMPEG                 OFF CACHE BOOL "Use FFmpeg" FORCE)
    set(WITH_XCB                    OFF CACHE BOOL "Use XCB" FORCE)
    set(WITH_TIGHTVNC_FILETRANSFER  OFF CACHE BOOL "Enable TightVNC filetransfer" FORCE)
    set(WITH_WEBSOCKETS             OFF CACHE BOOL "Enable WebSocket support" FORCE)
    set(WITH_IPv6                   ON  CACHE BOOL "Enable IPv6 support" FORCE)
    set(WITH_24BPP                  ON  CACHE BOOL "Allow 24 bpp" FORCE)
    set(WITH_THREADS                ON  CACHE BOOL "Use a threading library" FORCE)
    set(WITH_EXAMPLES               OFF CACHE BOOL "Build examples" FORCE)
    set(WITH_TESTS                  OFF CACHE BOOL "Build tests" FORCE)
    set(LIBVNCSERVER_INSTALL        OFF CACHE BOOL "Generate installation target" FORCE)

    add_subdirectory(${LIBVNC_RESOLVED} ${CMAKE_BINARY_DIR}/libvncserver)

    find_library(gles-lib GLESv2)

    add_library(native-vnc SHARED "${CMAKE_CURRENT_SOURCE_DIR}/native-vnc.cpp")

    # native-vnc.cpp uses [[nodiscard]] and C++ casts.
    set_target_properties(native-vnc PROPERTIES CXX_STANDARD 17 CXX_STANDARD_REQUIRED ON)

    target_include_directories(native-vnc PRIVATE
            "${LIBVNC_RESOLVED}/include"
            "${CMAKE_BINARY_DIR}/libvncserver/include")

    if(ZLIB_FOUND)
        # rfbclient.h keeps extendedClipboardServerCapabilities (and the zlib
        # decoder state) behind this guard, and libvncclient itself is compiled
        # with it. add_definitions() inside the subproject does not reach here.
        target_compile_definitions(native-vnc PRIVATE LIBVNCSERVER_HAVE_LIBZ)
    endif()

    if(AVNC_WITH_JPEG)
        # Same reason as above, and even more so here: rfbclient.h has struct
        # fields behind LIBVNCSERVER_HAVE_LIBJPEG (the Tight/turbojpeg state).
        # Compiling native-vnc.cpp without the macro while libvncclient has it
        # would silently move every member after them, i.e. read the framebuffer
        # pointer out of a cursor field. add_definitions() in the subproject
        # does not reach this target.
        target_compile_definitions(native-vnc PRIVATE LIBVNCSERVER_HAVE_LIBJPEG)
    endif()

    target_link_libraries(native-vnc PRIVATE
            vncclient
            ${gles-lib}
            ${log-lib})

    # Keep the symbols pulled in from libvncclient.a private, exactly like the
    # moonlight-core target above: exporting them would make the RFB internals
    # preemptible and break the static archive relocations.
    target_link_options(native-vnc PRIVATE -Wl,--exclude-libs,ALL)
else()
    message(WARNING
            "[moonlight-avnc] libvncserver sources NOT found; building "
            "placeholder libnative-vnc.so (VNC protocol disabled at runtime). "
            "Clone https://github.com/gujjwal00/libvncserver (branch avnc) into "
            "extern/libvncserver (or pass -DLIBVNC_SERVER_DIR) to enable it.")

    add_library(native-vnc SHARED "${CMAKE_CURRENT_SOURCE_DIR}/vnc_placeholder.cpp")
    target_link_libraries(native-vnc PRIVATE ${log-lib})
endif()

# =============================================================================
#  netlink neighbour table reader
#
#  Produces libnetlink-neigh.so, which com.limelight.util.NeighborNetlink loads
#  via System.loadLibrary("netlink-neigh").
#
#  Deliberately depends on nothing: no submodule, no prebuilt archive, no other
#  target. It is a single file of syscalls whose whole job is to still be able to
#  name a MAC address on an Android build where /proc/net/arp is hidden from the
#  app and the `ip` binary cannot be exec'd, so a broken libvncserver or a missing
#  moonlight-common-c checkout must never take it down as well.
# =============================================================================

add_library(netlink-neigh SHARED "${CMAKE_CURRENT_SOURCE_DIR}/netlink-neigh.c")
target_link_libraries(netlink-neigh PRIVATE ${log-lib})


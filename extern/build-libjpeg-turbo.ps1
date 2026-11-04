<#
  Cross-compiles the libjpeg-turbo source in this directory into a static
  libjpeg archive for every ABI the app builds for, and installs the results
  under extern/jpeg-prebuilt/<abi>/{include,lib}.

  Why this exists: AVNC pulls libjpeg-turbo through vcpkg (app/vcpkg.json) and
  therefore gets a libvncclient with LIBVNCSERVER_HAVE_LIBJPEG defined, which is
  what makes the Tight encoding - and with it the image quality setting -
  actually work. This project does not use vcpkg, so the library is built here
  once and fed to libvncserver's CMake via JPEG_INCLUDE_DIR / JPEG_LIBRARY.

  Re-run this after changing the NDK version, the ABI list, or adding an ABI to
  app/build.gradle.kts. app/src/main/cpp/CMakeLists.txt falls back to a
  JPEG-less libvncclient (with a warning) for any ABI that has no prebuilt
  archive here, so a missing directory degrades rather than breaks the build.

  Usage:  powershell -ExecutionPolicy Bypass -File .\build-libjpeg-turbo.ps1
#>
param(
    [string]$NdkPath = 'E:\Android\Sdk\ndk\28.2.13676358',
    [string]$CMakeDir = 'E:\Android\Sdk\cmake\3.22.1\bin',
    [string]$ApiLevel = '26',
    [string[]]$Abis = @('arm64-v8a', 'armeabi-v7a', 'x86_64')
)

$ErrorActionPreference = 'Stop'

$ExternDir = $PSScriptRoot
$SourceDir = Join-Path $ExternDir 'libjpeg-turbo'
$PrebuiltDir = Join-Path $ExternDir 'jpeg-prebuilt'
$ToolchainFile = Join-Path $NdkPath 'build\cmake\android.toolchain.cmake'
$CMakeExe = Join-Path $CMakeDir 'cmake.exe'
$NinjaExe = Join-Path $CMakeDir 'ninja.exe'
# libjpeg-turbo needs an x86 assembler; the NDK ships yasm, which it accepts.
$YasmExe = Join-Path $NdkPath 'prebuilt\windows-x86_64\bin\yasm.exe'

foreach ($path in @($SourceDir, $ToolchainFile, $CMakeExe, $NinjaExe)) {
    if (-not (Test-Path $path)) {
        throw "required path is missing: $path"
    }
}

function Invoke-CMakeStep {
    param([string]$WorkingDir, [string[]]$Arguments)
    Push-Location $WorkingDir
    try {
        & $CMakeExe @Arguments
        if ($LASTEXITCODE -ne 0) {
            throw "cmake $($Arguments[0]) failed with exit code $LASTEXITCODE"
        }
    } finally {
        Pop-Location
    }
}

function Build-Abi {
    param([string]$Abi, [switch]$WithoutSimd)

    $buildDir = Join-Path $ExternDir ("build\libjpeg-turbo\" + $Abi +
                                      $(if ($WithoutSimd) { '-nosimd' } else { '' }))
    $installDir = Join-Path $PrebuiltDir $Abi

    # Every element is written as a single interpolated string: in an array
    # literal the comma operator binds tighter than '+', so 'a=' + $b, 'c'
    # silently splits into two arguments.
    $simd = if ($WithoutSimd) { '0' } else { '1' }
    $configure = @(
        '-G', 'Ninja',
        '-S', $SourceDir,
        '-B', $buildDir,
        "-DCMAKE_MAKE_PROGRAM=$NinjaExe",
        "-DCMAKE_TOOLCHAIN_FILE=$ToolchainFile",
        "-DANDROID_ABI=$Abi",
        "-DANDROID_PLATFORM=android-$ApiLevel",
        '-DANDROID_ARM_MODE=arm',
        '-DCMAKE_BUILD_TYPE=Release',
        "-DCMAKE_INSTALL_PREFIX=$installDir",
        # Only the classic libjpeg API is needed: libvncserver carries its own
        # TurboJPEG shim (src/common/turbojpeg.c) which is written on top of it.
        '-DENABLE_SHARED=0',
        '-DENABLE_STATIC=1',
        '-DWITH_TURBOJPEG=0',
        '-DWITH_JAVA=0',
        '-DWITH_TOOLS=0',
        '-DWITH_TESTS=0',
        '-DWITH_FUZZ=0',
        "-DWITH_SIMD=$simd"
    )
    if ($Abi -like 'x86*' -and (Test-Path $YasmExe)) {
        $configure += "-DCMAKE_ASM_NASM_COMPILER=$($YasmExe -replace '\\', '/')"
    }

    $simdText = if ($WithoutSimd) { 'off' } else { 'on' }
    Write-Output ''
    Write-Output "=== $Abi (SIMD: $simdText) ==="

    New-Item -ItemType Directory -Force -Path $buildDir | Out-Null
    Invoke-CMakeStep -WorkingDir $buildDir -Arguments $configure
    Invoke-CMakeStep -WorkingDir $buildDir -Arguments @('--build', '.')
    if (Test-Path $installDir) {
        Remove-Item -Recurse -Force $installDir
    }
    Invoke-CMakeStep -WorkingDir $buildDir -Arguments @('--install', '.')

    $archive = Join-Path $installDir 'lib\libjpeg.a'
    $header = Join-Path $installDir 'include\jpeglib.h'
    if (-not (Test-Path $archive) -or -not (Test-Path $header)) {
        throw ("build for $Abi finished but " +
               "$(if (Test-Path $archive) { 'jpeglib.h' } else { 'libjpeg.a' }) is missing")
    }
    $sizeKB = [math]::Round((Get-Item $archive).Length / 1KB, 1)
    Write-Output ('installed: ' + $archive + ' (' + $sizeKB + ' KB)')
}

foreach ($abi in $Abis) {
    try {
        Build-Abi -Abi $abi
    } catch {
        Write-Output ($abi + ': SIMD build failed, retrying without SIMD. ' + $_.Exception.Message)
        Build-Abi -Abi $abi -WithoutSimd
    }
}

Write-Output ''
Write-Output 'All ABIs built. libvncserver can now be configured with WITH_JPEG=ON;'
Write-Output 'app/src/main/cpp/CMakeLists.txt picks the archive matching ANDROID_ABI.'

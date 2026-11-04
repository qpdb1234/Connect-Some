# =============================================================================
#  check-native.ps1 — Connect_Some native source self-check
#
#  Run AFTER copying the moonlight-common-c submodule, BEFORE building:
#      powershell -ExecutionPolicy Bypass -File .\scripts\check-native.ps1
#
#  It resolves the same moonlight-core directory the CMake build uses, verifies
#  every required input, prints the file manifest that will be compiled, and
#  tells you whether the build will produce the REAL libmoonlight-core.so or
#  fall back to the placeholder.
# =============================================================================

$ErrorActionPreference = 'Stop'

$cppDir = Join-Path $PSScriptRoot '..\app\src\main\cpp'
$cppDir = (Resolve-Path $cppDir).Path

# Candidate roots, mirroring CMakeLists.txt order (already absolute).
$candidates = @(
    (Join-Path $cppDir 'moonlight-core'),
    [System.IO.Path]::GetFullPath((Join-Path $cppDir '..\..\..\..\..\moonlight-android-master\moonlight-android-master\app\src\main\jni\moonlight-core'))
)

$abIs = @('armeabi-v7a', 'arm64-v8a', 'x86_64')

function Count-Files([string]$path, [string]$pattern) {
    if (-not (Test-Path $path)) { return -1 }
    return @(Get-ChildItem -Path $path -Filter $pattern -Recurse -File -EA SilentlyContinue).Count
}

$resolved = $null
foreach ($abs in $candidates) {
    if (-not (Test-Path $abs)) { continue }
    $srcCount = Count-Files (Join-Path $abs 'moonlight-common-c\src') '*.c'
    $jni = Join-Path $abs 'simplejni.c'
    if ($srcCount -gt 0 -and (Test-Path $jni)) { $resolved = $abs; break }
}

Write-Host ''
Write-Host '=== Connect_Some native self-check ===' -ForegroundColor Cyan
Write-Host "cpp root : $cppDir"

if ($null -eq $resolved) {
    Write-Host ''
    Write-Host 'RESULT: PLACEHOLDER build (real streaming disabled)' -ForegroundColor Yellow
    Write-Host 'moonlight-common-c sources were not found in any candidate location.'
    Write-Host 'Populate ONE of these directories:'
    foreach ($abs in $candidates) {
        Write-Host ("  - " + (Join-Path $abs 'moonlight-common-c')) -ForegroundColor DarkGray
    }
    Write-Host ''
    Write-Host 'Required layout inside moonlight-common-c:'
    Write-Host '  src\*.c            (protocol core: Connection.c, RtspConnection.c, ...)'
    Write-Host '  enet\*.c  +  enet\include\   (reliable UDP)'
    Write-Host '  nanors\rs.c  +  nanors\deps\obl\*.c   (rotation sensor math)'
    exit 2
}

Write-Host "resolved : $resolved" -ForegroundColor Green
Write-Host ''
Write-Host 'RESULT: REAL native build will run' -ForegroundColor Green
Write-Host ''

# ---- source manifest -------------------------------------------------------
Write-Host '--- Sources to be compiled ---' -ForegroundColor Cyan
$cc = Join-Path $resolved 'moonlight-common-c'
$manifest = [ordered]@{
    'common-c/src'        = Count-Files (Join-Path $cc 'src') '*.c'
    'common-c/enet'       = Count-Files (Join-Path $cc 'enet') '*.c'
    'common-c/nanors'     = Count-Files (Join-Path $cc 'nanors') '*.c'
    'glue/simplejni.c'    = (Test-Path (Join-Path $resolved 'simplejni.c'))
    'glue/callbacks.c'    = (Test-Path (Join-Path $resolved 'callbacks.c'))
    'glue/minisdl.c'      = (Test-Path (Join-Path $resolved 'minisdl.c'))
}
foreach ($k in $manifest.Keys) {
    Write-Host ("  {0,-20} {1}" -f $k, $manifest[$k])
}

# ---- required include dirs -------------------------------------------------
Write-Host ''
Write-Host '--- Include directories ---' -ForegroundColor Cyan
$incDirs = @(
    (Join-Path $cc 'enet\include'),
    (Join-Path $cc 'nanors\deps\obl'),
    (Join-Path $cc 'nanors'),
    (Join-Path $cc 'src'),
    (Join-Path $resolved 'openssl\include'),
    (Join-Path $resolved 'opus\include')
)
$incOk = $true
foreach ($d in $incDirs) {
    $exists = Test-Path $d
    if (-not $exists) { $incOk = $false }
    $color = if ($exists) { 'DarkGray' } else { 'Red' }
    Write-Host ("  [{0}] {1}" -f ($(if ($exists) {'OK'} else {'MISSING'})), $d) -ForegroundColor $color
}

# ---- prebuilt static libs per ABI -----------------------------------------
Write-Host ''
Write-Host '--- Prebuilt static libs (per ABI) ---' -ForegroundColor Cyan
$libsOk = $true
foreach ($abi in $abIs) {
    $crypto = Join-Path $resolved "openssl\$abi\libcrypto.a"
    $opus   = Join-Path $resolved "opus\$abi\libopus.a"
    $cOk = Test-Path $crypto
    $oOk = Test-Path $opus
    if (-not ($cOk -and $oOk)) { $libsOk = $false }
    Write-Host ("  {0,-12} libcrypto={1} libopus={2}" -f $abi, $cOk, $oOk) `
        -ForegroundColor $(if ($cOk -and $oOk) {'DarkGray'} else {'Red'})
}

# ---- verdict ---------------------------------------------------------------
Write-Host ''
$allOk = $incOk -and $libsOk
if ($allOk) {
    Write-Host 'ALL REQUIRED INPUTS PRESENT. Real GameStream/Sunshine streaming will build.' -ForegroundColor Green
    exit 0
} else {
    Write-Host 'Some inputs are MISSING (see RED entries above). Fix them before building.' -ForegroundColor Red
    exit 1
}

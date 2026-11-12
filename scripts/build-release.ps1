[CmdletBinding()]
param(
    [string]$Keystore = "",
    [string]$Alias = "dns2ss",
    [string]$OutDir = "",
    [switch]$Ci,
    [switch]$SkipTests,
    [switch]$NoSign,
    [switch]$Install
)

$ErrorActionPreference = "Stop"
$repo = Split-Path $PSScriptRoot -Parent
$sdk = if ($env:ANDROID_SDK_ROOT) {
    $env:ANDROID_SDK_ROOT
} elseif ($env:ANDROID_HOME) {
    $env:ANDROID_HOME
} else {
    $localProperties = Join-Path $repo "local.properties"
    $sdkLine = if (Test-Path $localProperties) {
        Get-Content $localProperties | Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1
    }
    if ($sdkLine) {
        $sdkLine.Substring(8).Replace('\\', '\').Replace('\:', ':')
    }
}
if (-not $sdk -or -not (Test-Path $sdk)) {
    throw "Set ANDROID_SDK_ROOT or ANDROID_HOME (or sdk.dir in local.properties)."
}
$jdk = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { throw "Set JAVA_HOME to JDK 17." }
$buildTools = Join-Path $sdk "build-tools\35.0.0"
$gradle = Join-Path $repo "gradlew.bat"
$apksigner = Join-Path $buildTools "apksigner.bat"
$zipalign = Join-Path $buildTools "zipalign.exe"

if (-not $Keystore) {
    $Keystore = if ($env:DNS2SS_KEYSTORE) {
        $env:DNS2SS_KEYSTORE
    } elseif ($env:DNS2SS_KEYSTORE_PATH) {
        $env:DNS2SS_KEYSTORE_PATH
    } else {
        Join-Path (Split-Path $repo -Parent) "dns2ss-release.jks"
    }
}
if (-not $OutDir) { $OutDir = Join-Path $repo "dist" }

foreach ($tool in @($gradle, $apksigner, $zipalign)) {
    if (-not (Test-Path $tool)) { throw "Required tool not found: $tool" }
}
if (-not $NoSign -and -not (Test-Path $Keystore)) {
    throw "Keystore not found: $Keystore"
}
if ($Ci -and -not $env:DNS2SS_KEYSTORE_PASSWORD) {
    throw "CI mode requires DNS2SS_KEYSTORE_PASSWORD"
}
if ($Ci -and -not $env:DNS2SS_KEY_PASSWORD) {
    # PKCS12 normally uses the same password for the store and key.
    $env:DNS2SS_KEY_PASSWORD = $env:DNS2SS_KEYSTORE_PASSWORD
}

$env:JAVA_HOME = $jdk
$env:Path = (Join-Path $jdk "bin") + ";" + $env:Path
$env:ANDROID_SDK_ROOT = $sdk
$env:ANDROID_HOME = $sdk
New-Item -ItemType Directory -Force $OutDir | Out-Null

$versionLine = Select-String -Path (Join-Path $repo "app\build.gradle.kts") -Pattern '^\s*versionName\s*=\s*"([^"]+)"'
if (-not $versionLine) { throw "Could not read versionName" }
$version = $versionLine.Matches[0].Groups[1].Value

$tasks = @(":app:assembleRelease")
if (-not $SkipTests) {
    $tasks += ":app:testDebugUnitTest"
    $tasks += ":app:lintRelease"
}

Write-Host "Building dns2ss $version with R8/resource shrinking..." -ForegroundColor Cyan
& $gradle -p $repo @tasks --console=plain
if ($LASTEXITCODE -ne 0) { throw "Gradle build failed ($LASTEXITCODE)" }

$unsigned = Join-Path $repo "app\build\outputs\apk\release\app-release-unsigned.apk"
$aligned = Join-Path $OutDir "dns2ss-$version-aligned.apk"
$signed = Join-Path $OutDir "dns2ss-$version-release.apk"
if (-not (Test-Path $unsigned)) { throw "Unsigned APK not found: $unsigned" }

& $zipalign -p -f 4 $unsigned $aligned
if ($LASTEXITCODE -ne 0) { throw "zipalign failed ($LASTEXITCODE)" }
& $zipalign -c -v 4 $aligned
if ($LASTEXITCODE -ne 0) { throw "zipalign verification failed ($LASTEXITCODE)" }

if ($NoSign) {
    Write-Host "Built aligned APK without signing: $aligned" -ForegroundColor Yellow
    return
}

$signArgs = @(
    "sign",
    "--ks", $Keystore,
    "--ks-key-alias", $Alias
)

if ($Ci) {
    Write-Host "CI signing using DNS2SS_KEYSTORE_PASSWORD / DNS2SS_KEY_PASSWORD..." -ForegroundColor Cyan
    $signArgs += @("--ks-pass", "env:DNS2SS_KEYSTORE_PASSWORD")
    if ($env:DNS2SS_KEY_PASSWORD) {
        $signArgs += @("--key-pass", "env:DNS2SS_KEY_PASSWORD")
    }
} else {
    Write-Host ""
    Write-Host "==========================================================================" -ForegroundColor Yellow
    Write-Host " Build complete. apksigner will now prompt for your KEYSTORE PASSWORD." -ForegroundColor Yellow
    Write-Host " If your key password differs, enter it at the second prompt." -ForegroundColor Yellow
    Write-Host "==========================================================================" -ForegroundColor Yellow
    Write-Host ""
}
$signArgs += @("--out", $signed, $aligned)
& $apksigner @signArgs
if ($LASTEXITCODE -ne 0) { throw "apksigner failed ($LASTEXITCODE)" }

& $apksigner verify --verbose --print-certs $signed
if ($LASTEXITCODE -ne 0) { throw "Signature verification failed ($LASTEXITCODE)" }

$mapping = Join-Path $repo "app\build\outputs\mapping\release\mapping.txt"
if (Test-Path $mapping) {
    Copy-Item $mapping (Join-Path $OutDir "mapping-$version.txt") -Force
}

$hash = (Get-FileHash $signed -Algorithm SHA256).Hash
Write-Host ""
Write-Host "Signed APK: $signed" -ForegroundColor Green
Write-Host "SHA-256:    $hash" -ForegroundColor Green

if ($Install) {
    $adb = if ($env:ADB) { $env:ADB } else { "adb" }
    $adbCommand = Get-Command $adb -ErrorAction SilentlyContinue
    if ($adbCommand) {
        Write-Warning "If a differently signed build is installed, uninstall it before installing this APK."
        & $adbCommand.Source install -r $signed
    } else {
        Write-Warning "adb not found; install manually: adb install -r `"$signed`""
    }
}

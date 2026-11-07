param(
    [string]$Root = (Split-Path -Parent $PSScriptRoot)
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path $Root).Path
$downloads = Join-Path $root 'third_party\downloads'
$jni = Join-Path $root 'app\src\main\jniLibs'
$libs = Join-Path $root 'app\libs'
New-Item -ItemType Directory -Force -Path $downloads, $jni, (Join-Path $jni 'arm64-v8a'), (Join-Path $jni 'x86_64'), $libs | Out-Null

function Get-Verified([string]$Url, [string]$Path, [string]$Sha256) {
    if (-not (Test-Path $Path)) {
        & curl.exe -L --fail --retry 3 -o $Path $Url
    }
    $actual = (Get-FileHash $Path -Algorithm SHA256).Hash
    if ($actual -ne $Sha256) { throw "Checksum mismatch for $Path`nExpected $Sha256`nActual   $actual" }
}

$hev = Join-Path $root 'app\libs\hev-socks5-tunnel.aar'
Get-Verified 'https://github.com/heiher/hev-socks5-tunnel/releases/download/2.19.3/hev-socks5-tunnel.aar' $hev 'AB6C581E6157680CFE5687EE7BBDCF95405C006C2DB3701879EC44B9E5F83836'

$arm = Join-Path $downloads 'sslocal-arm64.tar.xz'
$x86 = Join-Path $downloads 'sslocal-x86_64.tar.xz'
Get-Verified 'https://github.com/shadowsocks/shadowsocks-rust/releases/download/v1.25.0/shadowsocks-v1.25.0.aarch64-linux-android.tar.xz' $arm 'DF93DA8E63FC4B0AEE1457E7C7C5431F0A5AE2FA305D579153DC075F086E5278'
Get-Verified 'https://github.com/shadowsocks/shadowsocks-rust/releases/download/v1.25.0/shadowsocks-v1.25.0.x86_64-linux-android.tar.xz' $x86 'C0C82F088356AF640833C83EF4B80A4E26DA63B01C68D94A33BF9E515452BDBC'

$armOut = Join-Path $downloads 'arm64-extract'
$x86Out = Join-Path $downloads 'x86_64-extract'
New-Item -ItemType Directory -Force -Path $armOut, $x86Out | Out-Null
& tar.exe -xJf $arm -C $armOut sslocal
& tar.exe -xJf $x86 -C $x86Out sslocal
Copy-Item (Join-Path $armOut 'sslocal') (Join-Path $jni 'arm64-v8a\libsslocal.so') -Force
Copy-Item (Join-Path $x86Out 'sslocal') (Join-Path $jni 'x86_64\libsslocal.so') -Force

$licenses = Join-Path $root 'third_party\licenses'
New-Item -ItemType Directory -Force -Path $licenses | Out-Null
& curl.exe -L --fail -s -o (Join-Path $licenses 'hev-socks5-tunnel.LICENSE') 'https://raw.githubusercontent.com/heiher/hev-socks5-tunnel/main/LICENSE'
& curl.exe -L --fail -s -o (Join-Path $licenses 'shadowsocks-rust.LICENSE') 'https://raw.githubusercontent.com/shadowsocks/shadowsocks-rust/master/LICENSE'
& curl.exe -L --fail -s -o (Join-Path $licenses 'sockstun.LICENSE') 'https://raw.githubusercontent.com/heiher/sockstun/main/LICENSE'

Write-Host 'Third-party artifacts fetched and verified.'

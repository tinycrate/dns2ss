# dns2ss

A single-purpose Android app that sends DNS to a Pi-hole behind a Shadowsocks server, while optionally forwarding everything else directly. Built around Android's `VpnService`, [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel), and the official [shadowsocks-rust](https://github.com/shadowsocks/shadowsocks-rust) `sslocal` binary.

- App name: **dns2ss**
- Package: `com.tiny.dns2ss`
- Android 10+ (`minSdk 29`)
- `arm64-v8a` and `x86_64`
- Debug-signed APK for sideloading

## Modes

| Mode | DNS | Non-DNS traffic | Notes |
|---|---|---|---|
| **Fast** | System resolver → Pi-hole over SS | Native/kernel direct | Lowest overhead; hardcoded plain DNS to other servers bypasses |
| **Strict** | Every TCP/UDP port 53 → Pi-hole over SS | Direct through a userspace router | Catches hardcoded plain DNS; no DoH/DoT coverage |
| **Full** | Every TCP/UDP port 53 → Pi-hole over SS | Through Shadowsocks via a local SOCKS upstream | All app traffic goes through SS; local LAN resources are not directly reachable |

All modes are fail-closed for DNS: if the SS tunnel or Pi-hole is unavailable, DNS fails; there is no fallback to the system resolver.

## Architecture

```
VpnService TUN
  └─ hev-socks5-tunnel (socks5.pipeline enabled)
       └─ local SOCKS5 router (random port, random credentials)
            ├─ port 53 (TCP/UDP)
            │    ├─ shared DNS tunnel socket (one SS UDP association)
            │    ├─ TTL-aware DNS cache (positive + RFC 2308 negative)
            │    └─ sslocal tunnel local → SS server → 127.0.0.1:53530 (Pi-hole)
            └─ other ports
                 ├─ Fast: not routed into the TUN
                 ├─ Strict: direct socket
                 └─ Full: sslocal SOCKS local → SS server
```

- The virtual DNS addresses are `198.18.0.2` and `fd00::2`.
- Fast mode routes the virtual DNS addresses plus the current underlying network's DNS server IPs into the TUN. The diagnostics screen lists the discovered resolver IPs.
- Strict and Full add `0.0.0.0/0` and `::/0`.
- The app itself is excluded from the VPN so its SS connection and direct sockets do not loop.
- **Debug builds only** additionally exclude `com.android.shell`; this exists for local emulator testing with an SS server running under `adb shell`. Release builds do not exclude shell.
- DNS requests share one long-lived UDP socket to sslocal. Transaction IDs are multiplexed, so clients do not each create an SS UDP association.
- Responses are cached by normalized question + query options. TTLs are decremented on every cache hit; negative answers use the SOA TTL; truncated/error responses are not cached.
- `socks5.pipeline: true` removes a ~430 ms first-UDP-association stall observed with the previous configuration. Cold queries now measured around 2–13 ms on the local test path; cache hits are 0 ms.
- Network changes trigger a debounced service restart, recalculating Fast-mode resolver routes and rebuilding the SS connection.
- One-second stats polling was removed; diagnostics read live counters on demand.

## Requirements

- Android 10+ (`minSdk 29`; required by the hev AAR).
- `arm64-v8a` or `x86_64`; 32-bit ABIs are not bundled.
- A Shadowsocks server using AEAD or AEAD-2022 and **UDP relay enabled**.
- The server-side DNS target reachable from the SS server. Default: `127.0.0.1:53530`.
- Pi-hole/DNS listening on both TCP and UDP on that host/port.
- Private DNS disabled; browser/app DoH disabled or blocked where possible.

## Build

Requirements: JDK 17, Android SDK platform 35, build-tools 35.0.0.

```powershell
$env:JAVA_HOME = 'D:\AndroidToolchain\jdk-17'
$env:ANDROID_SDK_ROOT = 'D:\AndroidToolchain\sdk'
.\gradlew.bat assembleDebug
```

Set `sdk.dir` in `local.properties` for your machine. APK:

```
app/build/outputs/apk/debug/app-debug.apk
```

Test client:

```powershell
.\gradlew.bat :testclient:assembleDebug
```

## Usage

1. Open dns2ss.
2. Open **Settings** and paste a Shadowsocks URI (`ss://...`) and the Pi-hole target (`127.0.0.1:53530` by default).
3. Select Fast, Strict, or Full on the landing page.
4. Press Start and grant VPN permission.
5. Use the Quick Settings tile after the first successful start. The tile shows the active mode and reconnects on mode changes.

The landing page is status + mode + power only. Configuration is on the Settings page; counters, cache stats, routed resolver IPs, log verbosity, and logs are on Diagnostics.

### Quick Settings tile

- First tap before configuration/permission opens dns2ss with a message and starts the consent flow; it no longer silently opens the app.
- Tile state updates optimistically on tap and uses a monochrome tile icon.
- The tile displays the current mode as its subtitle.
- WSA reports no Quick Settings tile support (`check-support=false`), so the tile remains phone-test-only.

## Diagnostics

The Diagnostics page shows:

- run state, mode, uptime;
- Pi-hole target and SS server (no password);
- discovered Fast-mode routed DNS resolver IPs;
- DNS cache entries/hits/misses;
- DNS query/response/pending counts;
- tunnel/direct/full byte counters and errors;
- Private DNS mode;
- log verbosity (Off / Errors / Warnings / Info / Debug) and a bounded in-memory log.

Changing log verbosity applies on the next VPN start. Default is **Warnings**; sslocal debug logging is not enabled by default.

## Testing performed (WSA, Android 13 x86_64)

- JVM tests: 13 tests, all passing (SS URI, config builders, DNS cache).
- **Fast**: system DNS worked; fake-resolver TCP/UDP failed by design; diagnostics listed the underlying resolver `172.29.144.1`; HTTPS 200; non-DNS NTP OK.
- **Strict**: fake-resolver UDP/TCP both succeeded (proves port-53 capture and source forging); cache reuse queries 0 ms and cold/new-socket queries ~2 ms after the pipeline fix; HTTPS 200; NTP OK; SS server logs saw only DNS.
- **Full**: fake-resolver UDP/TCP succeeded; non-DNS NTP went through SOCKS; HTTPS 200; SS server logs saw both DNS and `example.com:443`.
- Diagnostics after a Strict run: `Cache: 2 entries, 8 hits, 2 misses`.
- Mode switches restart the VPN cleanly and update the landing status.
- Stop removes the VPN network and terminates the `sslocal` child process.

The WSA tests used a test SS server running locally under `adb shell`, the debug-only shell exclusion, and `8.8.8.8:53` as the Pi-hole target. The shipped default remains `127.0.0.1:53530`; validate against the real Pi-hole on the phone.

## Limitations

- **Private DNS / DoT** (port 853) bypasses port 53. Disable it; the app warns but does not block it.
- **DoH** (port 443) bypasses port 53. Disable browser secure DNS and block DoH hostnames in Pi-hole.
- **Fast mode** intentionally does not capture hardcoded plain DNS to arbitrary servers.
- **Loopback DNS** (`127.0.0.1:53`/`::1`) cannot be captured by a VPN.
- Apps that explicitly bind sockets to the underlying network bypass the VPN.
- The SS server hostname is resolved by `sslocal` using the underlying network before/outside the tunnel; use an IP if that metadata leak matters.
- No per-app mode, always-on/lockdown, or 32-bit ABIs.
- The APK is debug-signed.
- Network-change restart is implemented and the same restart path is exercised by mode changes; an actual Wi-Fi/cellular transition still needs a final phone test.
- Real Pi-hole and physical-phone performance/tile testing remain to be done.
- WSA can differ from a real device; the phone is the final acceptance device.

## Third-party components

- `app/libs/hev-socks5-tunnel.aar` — hev-socks5-tunnel 2.19.3, MIT.
- `app/src/main/jniLibs/{arm64-v8a,x86_64}/libsslocal.so` — shadowsocks-rust v1.25.0, MIT.
- SockTun (MIT) was used as a reference for the VpnService/hev/QS-tile lifecycle.
- AndroidX, Material Components — Apache-2.0.

See `THIRD_PARTY_NOTICES.md` and `third_party/licenses/`.

## Test tools

- `tools/testclient` — separate APK that sends fake-resolver UDP/TCP DNS (including repeated same-socket and fresh-socket queries), NTP, and HTTPS checks; logs under tag `TestClient`.
- `tools/testdns/TestDnsServer.java` — tiny JDK DNS server returning `10.9.9.9` for test queries.
- `scripts/fetch-third-party.ps1` — optional re-download/verification helper for the vendored binaries.

## License

MIT. See `LICENSE`.
